package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.PasswordChangeNotAllowedException;
import com.bablsoft.accessflow.core.api.PasswordIncorrectException;
import com.bablsoft.accessflow.core.api.SessionRevocationService;
import com.bablsoft.accessflow.core.api.TotpAlreadyEnabledException;
import com.bablsoft.accessflow.core.api.TotpEnrollment;
import com.bablsoft.accessflow.core.api.TotpInvalidCodeException;
import com.bablsoft.accessflow.core.api.TotpNotEnabledException;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.core.internal.totp.TotpCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUserProfileServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock CredentialEncryptionService encryptionService;
    @Mock TotpCodec totpCodec;
    @Mock SessionRevocationService sessionRevocationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DefaultUserProfileService service;
    private UserEntity user;
    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultUserProfileService(userRepository, passwordEncoder,
                encryptionService, totpCodec, objectMapper, sessionRevocationService);
        var org = new OrganizationEntity();
        org.setId(orgId);
        org.setName("Acme");
        user = new UserEntity();
        user.setId(userId);
        user.setOrganization(org);
        user.setEmail("alice@example.com");
        user.setDisplayName("Alice");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setPasswordHash("hashed");
        user.setActive(true);
    }

    @Test
    void getProfileReturnsUserView() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        var view = service.getProfile(userId);
        assertThat(view.email()).isEqualTo("alice@example.com");
        assertThat(view.displayName()).isEqualTo("Alice");
        assertThat(view.totpEnabled()).isFalse();
    }

    @Test
    void getProfileThrowsWhenUserMissing() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProfile(userId))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void updateDisplayNameWritesToEntity() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        var view = service.updateDisplayName(userId, "New Name");
        assertThat(view.displayName()).isEqualTo("New Name");
        assertThat(user.getDisplayName()).isEqualTo("New Name");
    }

    @Test
    void changePasswordVerifiesCurrentAndRotatesHashAndRevokesSessions() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old", "hashed")).thenReturn(true);
        when(passwordEncoder.encode("new-secret")).thenReturn("new-hash");

        service.changePassword(userId, "old", "new-secret");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(sessionRevocationService).revokeAllSessions(userId);
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        assertThatThrownBy(() -> service.changePassword(userId, "wrong", "new-secret"))
                .isInstanceOf(PasswordIncorrectException.class);
        verify(sessionRevocationService, never()).revokeAllSessions(any());
    }

    @Test
    void changePasswordRejectedForSamlAccount() {
        user.setAuthProvider(AuthProviderType.SAML);
        user.setPasswordHash(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.changePassword(userId, "x", "y"))
                .isInstanceOf(PasswordChangeNotAllowedException.class);
    }

    @Test
    void startTotpEnrollmentStoresEncryptedSecretAndReturnsEnrollment() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(totpCodec.newSecret()).thenReturn("secret-xyz");
        when(encryptionService.encrypt("secret-xyz")).thenReturn("enc-secret");
        when(totpCodec.buildEnrollment("secret-xyz", "AccessFlow", "alice@example.com"))
                .thenReturn(new TotpEnrollment("secret-xyz", "otpauth://...", "data:image/png;base64,..."));

        var enrollment = service.startTotpEnrollment(userId);

        assertThat(enrollment.secret()).isEqualTo("secret-xyz");
        assertThat(user.getTotpSecretEncrypted()).isEqualTo("enc-secret");
        assertThat(user.isTotpEnabled()).isFalse();
    }

    @Test
    void startTotpEnrollmentRejectedWhenAlreadyEnabled() {
        user.setTotpEnabled(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.startTotpEnrollment(userId))
                .isInstanceOf(TotpAlreadyEnabledException.class);
    }

    @Test
    void confirmTotpEnrollmentValidatesCodeAndStoresBackupCodes() {
        user.setTotpSecretEncrypted("enc-secret");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(encryptionService.decrypt("enc-secret")).thenReturn("secret-xyz");
        when(totpCodec.verifyCode("secret-xyz", "123456")).thenReturn(true);
        when(totpCodec.generateRecoveryCodes()).thenReturn(List.of("a", "b", "c"));
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "h:" + inv.getArgument(0));
        when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));

        var result = service.confirmTotpEnrollment(userId, "123456");

        assertThat(user.isTotpEnabled()).isTrue();
        assertThat(result.backupCodes()).containsExactly("a", "b", "c");
        assertThat(user.getTotpBackupCodesEncrypted()).startsWith("enc:");
    }

    @Test
    void confirmTotpEnrollmentRejectsInvalidCode() {
        user.setTotpSecretEncrypted("enc-secret");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(encryptionService.decrypt("enc-secret")).thenReturn("secret-xyz");
        when(totpCodec.verifyCode("secret-xyz", "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.confirmTotpEnrollment(userId, "000000"))
                .isInstanceOf(TotpInvalidCodeException.class);
        assertThat(user.isTotpEnabled()).isFalse();
    }

    @Test
    void confirmTotpEnrollmentRejectsWhenEnrollmentNotStarted() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.confirmTotpEnrollment(userId, "123456"))
                .isInstanceOf(TotpNotEnabledException.class);
    }

    @Test
    void confirmTotpEnrollmentRejectsWhenAlreadyEnabled() {
        user.setTotpEnabled(true);
        user.setTotpSecretEncrypted("enc");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.confirmTotpEnrollment(userId, "123456"))
                .isInstanceOf(TotpAlreadyEnabledException.class);
    }

    @Test
    void disableTotpClearsSecretAndCodesAndRevokesSessions() {
        user.setTotpEnabled(true);
        user.setTotpSecretEncrypted("enc-secret");
        user.setTotpBackupCodesEncrypted("enc-codes");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "hashed")).thenReturn(true);

        service.disableTotp(userId, "password");

        assertThat(user.isTotpEnabled()).isFalse();
        assertThat(user.getTotpSecretEncrypted()).isNull();
        assertThat(user.getTotpBackupCodesEncrypted()).isNull();
        verify(sessionRevocationService).revokeAllSessions(userId);
    }

    @Test
    void disableTotpRejectsWhenNotEnabled() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.disableTotp(userId, "password"))
                .isInstanceOf(TotpNotEnabledException.class);
    }

    @Test
    void disableTotpRejectsWrongPassword() {
        user.setTotpEnabled(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        assertThatThrownBy(() -> service.disableTotp(userId, "wrong"))
                .isInstanceOf(PasswordIncorrectException.class);
        assertThat(user.isTotpEnabled()).isTrue();
    }

    @Test
    void disableTotpRejectedForSamlAccount() {
        user.setAuthProvider(AuthProviderType.SAML);
        user.setTotpEnabled(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.disableTotp(userId, "x"))
                .isInstanceOf(PasswordChangeNotAllowedException.class);
    }
}
