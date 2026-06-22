package com.bablsoft.accessflow.notifications.internal.push;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.notifications.internal.config.PushProperties;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.PushVapidConfigEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.PushVapidConfigRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.UUID;

/**
 * Resolves the deployment VAPID keypair (AF-444). Resolution order, mirroring
 * {@code SamlSpKeyProvider}:
 *
 * <ol>
 *   <li>{@link PushProperties} env override — when both raw base64url keys are set, use them
 *       verbatim (no persistence, operator owns the lifecycle).</li>
 *   <li>{@code push_vapid_config} row — decrypt the private key and return.</li>
 *   <li>Auto-generate a P-256 keypair, encrypt the private scalar with
 *       {@link CredentialEncryptionService}, persist into a single row, return.</li>
 * </ol>
 *
 * Step 3 is single-flight (a deployment-wide lock) so two concurrent first-time callers don't
 * persist competing keys. The resolved material is cached in memory after the first call.
 */
@Service
@RequiredArgsConstructor
public class PushVapidKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(PushVapidKeyProvider.class);
    private static final Object LOCK = new Object();

    private final PushVapidConfigRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final PushProperties properties;

    private volatile VapidKeyMaterial cached;

    public VapidKeyMaterial resolve() {
        var local = cached;
        if (local != null) {
            return local;
        }
        if (properties.hasExplicitKeyPair()) {
            local = fromBase64(properties.publicKey(), properties.privateKey(), properties.subject());
            cached = local;
            return local;
        }
        return loadOrGenerate();
    }

    @Transactional
    VapidKeyMaterial loadOrGenerate() {
        synchronized (LOCK) {
            if (cached != null) {
                return cached;
            }
            var existing = repository.findFirstByOrderByCreatedAtAsc().orElse(null);
            if (existing != null) {
                var privateKeyBase64 = encryptionService.decrypt(existing.getPrivateKeyEncrypted());
                cached = fromBase64(existing.getPublicKey(), privateKeyBase64, existing.getSubject());
                return cached;
            }
            log.info("Auto-generating deployment VAPID keypair");
            var keyPair = WebPushCrypto.generateVapidKeyPair();
            var publicKey = WebPushCrypto.encodePublicKey((ECPublicKey) keyPair.getPublic());
            var privateScalar = WebPushCrypto.encodePrivateKey((ECPrivateKey) keyPair.getPrivate());
            var publicBase64 = WebPushCrypto.base64Url(publicKey);
            var privateBase64 = WebPushCrypto.base64Url(privateScalar);
            var entity = new PushVapidConfigEntity();
            entity.setId(UUID.randomUUID());
            entity.setPublicKey(publicBase64);
            entity.setPrivateKeyEncrypted(encryptionService.encrypt(privateBase64));
            entity.setSubject(properties.subject());
            repository.save(entity);
            cached = new VapidKeyMaterial((ECPrivateKey) keyPair.getPrivate(), publicKey,
                    publicBase64, properties.subject());
            return cached;
        }
    }

    private static VapidKeyMaterial fromBase64(String publicBase64, String privateBase64,
                                               String subject) {
        var publicBytes = WebPushCrypto.decodeBase64Url(publicBase64);
        var privateKey = WebPushCrypto.decodePrivateKey(WebPushCrypto.decodeBase64Url(privateBase64));
        return new VapidKeyMaterial(privateKey, publicBytes, publicBase64, subject);
    }

    /** Resolved VAPID material: the signing key, the public point, its base64url form, the subject. */
    public record VapidKeyMaterial(ECPrivateKey privateKey, byte[] publicKey,
                                   String publicKeyBase64Url, String subject) {
    }
}
