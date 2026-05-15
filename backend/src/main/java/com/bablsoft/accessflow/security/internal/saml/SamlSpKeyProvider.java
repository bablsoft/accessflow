package com.bablsoft.accessflow.security.internal.saml;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.security.internal.persistence.entity.SamlConfigEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.SamlConfigRepository;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the SP signing keypair used to sign AuthnRequest messages and ship in the SP
 * metadata XML.
 *
 * Resolution order:
 * 1. {@link SamlSpKeyMaterialProperties} (env-var override) — when both PEMs are set, take them
 *    verbatim. No encryption, no persistence — the operator owns the lifecycle.
 * 2. {@code saml_config.sp_private_key_pem + sp_certificate_pem} — when present, decrypt the
 *    private key and return.
 * 3. Auto-generate a self-signed RSA-2048 keypair (CN=accessflow-saml-sp), encrypt the private
 *    key with {@link CredentialEncryptionService}, persist into the same row, return.
 *
 * Step 3 is single-flight per organization via a {@link ConcurrentHashMap}-backed lock so two
 * concurrent first-time requests don't race and persist different keys.
 */
@Service
@RequiredArgsConstructor
public class SamlSpKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(SamlSpKeyProvider.class);

    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    private static final String CERT_SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String CERT_SUBJECT = "CN=accessflow-saml-sp";
    private static final int CERT_VALIDITY_YEARS = 10;

    private final SamlConfigRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final SamlSpKeyMaterialProperties keyMaterial;
    private final ConcurrentHashMap<UUID, Object> perOrgLocks = new ConcurrentHashMap<>();

    /**
     * Returns the active SP keypair for the given organization. May read from env vars, or
     * read+decrypt from {@code saml_config}, or generate+persist on first call.
     */
    @Transactional
    public SpKeyPair resolve(UUID organizationId) {
        if (keyMaterial.isConfigured()) {
            return parse(keyMaterial.signingKeyPem(), keyMaterial.signingCertPem());
        }
        var lock = perOrgLocks.computeIfAbsent(organizationId, k -> new Object());
        synchronized (lock) {
            var entity = repository.findByOrganizationId(organizationId)
                    .orElseThrow(() -> new IllegalStateException(
                            "saml_config row not seeded for organization " + organizationId));
            if (entity.getSpPrivateKeyPem() != null && entity.getSpCertificatePem() != null) {
                var keyPem = encryptionService.decrypt(entity.getSpPrivateKeyPem());
                return parse(keyPem, entity.getSpCertificatePem());
            }
            log.info("Auto-generating SAML SP keypair for organization {}", organizationId);
            var generated = generate();
            entity.setSpPrivateKeyPem(encryptionService.encrypt(generated.privateKeyPem()));
            entity.setSpCertificatePem(generated.certificatePem());
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            return parse(generated.privateKeyPem(), generated.certificatePem());
        }
    }

    private SpKeyPair generate() {
        try {
            var keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyPairGenerator.initialize(KEY_SIZE, new SecureRandom());
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            var subject = new X500Name(CERT_SUBJECT);
            var notBefore = Date.from(Instant.now().minus(1, ChronoUnit.HOURS));
            var notAfter = Date.from(Instant.now().plus(CERT_VALIDITY_YEARS * 365L, ChronoUnit.DAYS));
            var serial = BigInteger.valueOf(System.currentTimeMillis());
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject, serial, notBefore, notAfter, subject, keyPair.getPublic());
            var signer = new JcaContentSignerBuilder(CERT_SIGNATURE_ALGORITHM).build(keyPair.getPrivate());
            var certHolder = builder.build(signer);
            var certificate = new JcaX509CertificateConverter().getCertificate(certHolder);
            return new SpKeyPair(
                    keyPair.getPrivate(),
                    certificate,
                    toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                    toPem("CERTIFICATE", certificate.getEncoded()));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate SAML SP keypair", ex);
        }
    }

    private SpKeyPair parse(String privateKeyPem, String certificatePem) {
        try {
            var keyBytes = decodePem(privateKeyPem);
            var keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            var certFactory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) certFactory.generateCertificate(
                    new java.io.ByteArrayInputStream(
                            certificatePem.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return new SpKeyPair(privateKey, certificate, privateKeyPem, certificatePem);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse SAML SP keypair", ex);
        }
    }

    private static byte[] decodePem(String pem) {
        var base64 = pem
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(base64);
    }

    private static String toPem(String type, byte[] der) {
        var encoded = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----\n";
    }

    public record SpKeyPair(PrivateKey privateKey, X509Certificate certificate,
                            String privateKeyPem, String certificatePem) {
    }

    /** Used by tests to parse an IdP-supplied PEM cert into an {@link X509Certificate}. */
    public static X509Certificate parseCertificate(String pem) {
        try {
            var factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(
                    new java.io.ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse X.509 certificate", ex);
        }
    }
}
