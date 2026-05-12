package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.PasswordChangeNotAllowedException;
import com.bablsoft.accessflow.core.api.PasswordIncorrectException;
import com.bablsoft.accessflow.core.api.SessionRevocationService;
import com.bablsoft.accessflow.core.api.TotpAlreadyEnabledException;
import com.bablsoft.accessflow.core.api.TotpConfirmationResult;
import com.bablsoft.accessflow.core.api.TotpEnrollment;
import com.bablsoft.accessflow.core.api.TotpInvalidCodeException;
import com.bablsoft.accessflow.core.api.TotpNotEnabledException;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserProfileService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.core.internal.totp.TotpCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultUserProfileService implements UserProfileService {

    private static final String TOTP_ISSUER = "AccessFlow";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CredentialEncryptionService encryptionService;
    private final TotpCodec totpCodec;
    private final ObjectMapper objectMapper;
    private final SessionRevocationService sessionRevocationService;

    @Override
    @Transactional(readOnly = true)
    public UserView getProfile(UUID userId) {
        return toView(loadUser(userId));
    }

    @Override
    @Transactional
    public UserView updateDisplayName(UUID userId, String displayName) {
        var user = loadUser(userId);
        user.setDisplayName(displayName);
        return toView(user);
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        var user = loadUser(userId);
        requireLocalAccount(user, "Password change is not allowed for non-local accounts");
        verifyCurrentPassword(user, currentPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        sessionRevocationService.revokeAllSessions(user.getId());
    }

    @Override
    @Transactional
    public TotpEnrollment startTotpEnrollment(UUID userId) {
        var user = loadUser(userId);
        requireLocalAccount(user, "Two-factor authentication is not allowed for non-local accounts");
        if (user.isTotpEnabled()) {
            throw new TotpAlreadyEnabledException("Two-factor authentication is already enabled");
        }
        var secret = totpCodec.newSecret();
        user.setTotpSecretEncrypted(encryptionService.encrypt(secret));
        return totpCodec.buildEnrollment(secret, TOTP_ISSUER, user.getEmail());
    }

    @Override
    @Transactional
    public TotpConfirmationResult confirmTotpEnrollment(UUID userId, String code) {
        var user = loadUser(userId);
        requireLocalAccount(user, "Two-factor authentication is not allowed for non-local accounts");
        if (user.isTotpEnabled()) {
            throw new TotpAlreadyEnabledException("Two-factor authentication is already enabled");
        }
        if (user.getTotpSecretEncrypted() == null) {
            throw new TotpNotEnabledException("Start enrollment before confirming");
        }
        var secret = encryptionService.decrypt(user.getTotpSecretEncrypted());
        if (!totpCodec.verifyCode(secret, code)) {
            throw new TotpInvalidCodeException("Invalid verification code");
        }
        var plaintextCodes = totpCodec.generateRecoveryCodes();
        var hashes = plaintextCodes.stream().map(passwordEncoder::encode).toList();
        user.setTotpBackupCodesEncrypted(encryptionService.encrypt(serialize(hashes)));
        user.setTotpEnabled(true);
        return new TotpConfirmationResult(plaintextCodes);
    }

    @Override
    @Transactional
    public void disableTotp(UUID userId, String currentPassword) {
        var user = loadUser(userId);
        requireLocalAccount(user, "Two-factor authentication is not allowed for non-local accounts");
        if (!user.isTotpEnabled()) {
            throw new TotpNotEnabledException("Two-factor authentication is not enabled");
        }
        verifyCurrentPassword(user, currentPassword);
        user.setTotpEnabled(false);
        user.setTotpSecretEncrypted(null);
        user.setTotpBackupCodesEncrypted(null);
        sessionRevocationService.revokeAllSessions(user.getId());
    }

    private UserEntity loadUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    private void requireLocalAccount(UserEntity user, String message) {
        if (user.getAuthProvider() != AuthProviderType.LOCAL) {
            throw new PasswordChangeNotAllowedException(message);
        }
    }

    private void verifyCurrentPassword(UserEntity user, String currentPassword) {
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new PasswordIncorrectException("Current password is incorrect");
        }
    }

    private String serialize(List<String> hashes) {
        try {
            return objectMapper.writeValueAsString(hashes);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Failed to serialize backup code hashes", ex);
        }
    }

    private UserView toView(UserEntity entity) {
        return new UserView(
                entity.getId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getRole(),
                entity.getOrganization().getId(),
                entity.isActive(),
                entity.getAuthProvider(),
                entity.getPasswordHash(),
                entity.getLastLoginAt(),
                entity.getPreferredLanguage(),
                entity.isTotpEnabled(),
                entity.getCreatedAt()
        );
    }
}
