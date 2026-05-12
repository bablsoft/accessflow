package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.TotpVerificationService;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.core.internal.totp.TotpCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultTotpVerificationService implements TotpVerificationService {

    private final UserRepository userRepository;
    private final TotpCodec totpCodec;
    private final CredentialEncryptionService encryptionService;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return userRepository.findById(userId)
                .map(UserEntity::isTotpEnabled)
                .orElse(false);
    }

    @Override
    @Transactional
    public boolean verify(UUID userId, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        var user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isTotpEnabled() || user.getTotpSecretEncrypted() == null) {
            return false;
        }
        var secret = encryptionService.decrypt(user.getTotpSecretEncrypted());
        if (totpCodec.verifyCode(secret, code)) {
            return true;
        }
        return consumeBackupCode(user, code);
    }

    private boolean consumeBackupCode(UserEntity user, String code) {
        var encryptedBlob = user.getTotpBackupCodesEncrypted();
        if (encryptedBlob == null || encryptedBlob.isBlank()) {
            return false;
        }
        List<String> hashes;
        try {
            var plaintext = encryptionService.decrypt(encryptedBlob);
            hashes = new ArrayList<>(objectMapper.readValue(plaintext, new TypeReference<List<String>>() {}));
        } catch (JacksonException ex) {
            log.error("Failed to parse backup codes for user {}", user.getId(), ex);
            return false;
        }
        for (int i = 0; i < hashes.size(); i++) {
            if (passwordEncoder.matches(code, hashes.get(i))) {
                hashes.remove(i);
                try {
                    var rewritten = objectMapper.writeValueAsString(hashes);
                    user.setTotpBackupCodesEncrypted(encryptionService.encrypt(rewritten));
                } catch (JacksonException ex) {
                    log.error("Failed to re-serialize backup codes for user {}", user.getId(), ex);
                    return false;
                }
                return true;
            }
        }
        return false;
    }
}
