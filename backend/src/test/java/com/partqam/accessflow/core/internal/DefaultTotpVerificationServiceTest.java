package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.core.internal.totp.TotpCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultTotpVerificationServiceTest {

    @Mock UserRepository userRepository;
    @Mock TotpCodec totpCodec;
    @Mock CredentialEncryptionService encryptionService;
    @Mock PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DefaultTotpVerificationService service;
    private UserEntity user;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultTotpVerificationService(userRepository, totpCodec,
                encryptionService, passwordEncoder, objectMapper);
        user = new UserEntity();
        user.setId(userId);
        user.setTotpEnabled(true);
        user.setTotpSecretEncrypted("enc-secret");
    }

    @Test
    void isEnabledReturnsFalseWhenUserMissing() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        assertThat(service.isEnabled(userId)).isFalse();
    }

    @Test
    void isEnabledReflectsEntityFlag() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        assertThat(service.isEnabled(userId)).isTrue();
    }

    @Test
    void verifyReturnsFalseForBlankCode() {
        assertThat(service.verify(userId, "")).isFalse();
        assertThat(service.verify(userId, null)).isFalse();
    }

    @Test
    void verifyReturnsFalseWhenTotpNotEnabled() {
        user.setTotpEnabled(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        assertThat(service.verify(userId, "123456")).isFalse();
    }

    @Test
    void verifyAcceptsValidTotpCode() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(encryptionService.decrypt("enc-secret")).thenReturn("secret-xyz");
        when(totpCodec.verifyCode("secret-xyz", "123456")).thenReturn(true);

        assertThat(service.verify(userId, "123456")).isTrue();
    }

    @Test
    void verifyFallsBackToBackupCodeAndConsumesIt() throws Exception {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(encryptionService.decrypt("enc-secret")).thenReturn("secret-xyz");
        when(totpCodec.verifyCode("secret-xyz", "BACKUP-1")).thenReturn(false);

        var hashes = new ArrayList<String>(List.of("h:BACKUP-1", "h:BACKUP-2"));
        var encryptedBlob = "enc-codes";
        user.setTotpBackupCodesEncrypted(encryptedBlob);
        when(encryptionService.decrypt(encryptedBlob)).thenReturn(objectMapper.writeValueAsString(hashes));
        when(passwordEncoder.matches("BACKUP-1", "h:BACKUP-1")).thenReturn(true);
        when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));

        assertThat(service.verify(userId, "BACKUP-1")).isTrue();
        // After consumption, the encrypted backup blob is rewritten without the used hash
        assertThat(user.getTotpBackupCodesEncrypted()).startsWith("enc:");
        assertThat(user.getTotpBackupCodesEncrypted()).doesNotContain("h:BACKUP-1");
    }

    @Test
    void verifyReturnsFalseWhenBackupCodeNotFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(encryptionService.decrypt("enc-secret")).thenReturn("secret-xyz");
        when(totpCodec.verifyCode("secret-xyz", "NOPE")).thenReturn(false);
        user.setTotpBackupCodesEncrypted("enc-codes");
        when(encryptionService.decrypt("enc-codes")).thenReturn("[\"h1\",\"h2\"]");
        when(passwordEncoder.matches("NOPE", "h1")).thenReturn(false);
        when(passwordEncoder.matches("NOPE", "h2")).thenReturn(false);

        assertThat(service.verify(userId, "NOPE")).isFalse();
    }

    @Test
    void verifyReturnsFalseWhenBackupBlobMissing() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(encryptionService.decrypt("enc-secret")).thenReturn("secret-xyz");
        when(totpCodec.verifyCode("secret-xyz", "NOPE")).thenReturn(false);
        // No backup codes set
        assertThat(service.verify(userId, "NOPE")).isFalse();
    }
}
