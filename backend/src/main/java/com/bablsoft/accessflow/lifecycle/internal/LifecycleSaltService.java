package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleSaltEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.LifecycleSaltRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Owns the per-organization pseudonymization salt (AF-499): lazily creates it on first use, decrypts
 * it for the masking layer, and rotates it on demand. The plaintext salt never leaves this service
 * except as a masking parameter; at rest it is AES-256-GCM encrypted.
 */
@Service
@RequiredArgsConstructor
class LifecycleSaltService {

    private static final int SALT_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();

    private final LifecycleSaltRepository repository;
    private final CredentialEncryptionService credentialEncryptionService;
    private final Clock clock;

    /** @return the current decrypted salt for the org, creating one on first use. */
    @Transactional
    String currentSalt(UUID organizationId) {
        var entity = repository.findById(organizationId)
                .orElseGet(() -> create(organizationId));
        return credentialEncryptionService.decrypt(entity.getSaltEncrypted());
    }

    /** Rotates the org's salt; previously hashed values stay hashed (irreversible). */
    @Transactional
    void rotate(UUID organizationId) {
        var entity = repository.findById(organizationId).orElseGet(() -> create(organizationId));
        entity.setSaltEncrypted(credentialEncryptionService.encrypt(newSalt()));
        entity.setVersion(entity.getVersion() + 1);
        entity.setRotatedAt(clock.instant());
        repository.save(entity);
    }

    private LifecycleSaltEntity create(UUID organizationId) {
        var entity = new LifecycleSaltEntity();
        entity.setOrganizationId(organizationId);
        entity.setSaltEncrypted(credentialEncryptionService.encrypt(newSalt()));
        entity.setVersion(1);
        entity.setRotatedAt(clock.instant());
        entity.setCreatedAt(clock.instant());
        return repository.save(entity);
    }

    private String newSalt() {
        var bytes = new byte[SALT_BYTES];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
