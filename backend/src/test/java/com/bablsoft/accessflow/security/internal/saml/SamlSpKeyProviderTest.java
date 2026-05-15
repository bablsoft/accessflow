package com.bablsoft.accessflow.security.internal.saml;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.security.internal.persistence.entity.SamlConfigEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.SamlConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlSpKeyProviderTest {

    @Mock SamlConfigRepository repository;
    @Mock CredentialEncryptionService encryptionService;

    private final UUID orgId = UUID.randomUUID();

    @Test
    void envVarOverrideShortCircuitsRepositoryLookup() throws Exception {
        var generated = generateKeyPair();
        var props = new SamlSpKeyMaterialProperties(generated.privateKeyPem(), generated.certificatePem());
        var provider = new SamlSpKeyProvider(repository, encryptionService, props);

        var result = provider.resolve(orgId);

        assertThat(result.privateKey()).isNotNull();
        assertThat(result.certificate()).isNotNull();
        verify(repository, never()).findByOrganizationId(orgId);
    }

    @Test
    void persistedKeypairIsDecryptedAndReturned() throws Exception {
        var generated = generateKeyPair();
        var entity = seeded();
        entity.setSpPrivateKeyPem("ENC(key)");
        entity.setSpCertificatePem(generated.certificatePem());
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("ENC(key)")).thenReturn(generated.privateKeyPem());

        var provider = new SamlSpKeyProvider(repository, encryptionService, emptyMaterial());
        var result = provider.resolve(orgId);

        assertThat(result.privateKey()).isNotNull();
        assertThat(result.certificate().getSubjectX500Principal().getName())
                .contains("accessflow-test-sp");
        verify(encryptionService).decrypt("ENC(key)");
    }

    @Test
    void autoGeneratesAndPersistsWhenMissing() {
        var entity = seeded();
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(encryptionService.encrypt(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");
        when(repository.save(org.mockito.ArgumentMatchers.any(SamlConfigEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var provider = new SamlSpKeyProvider(repository, encryptionService, emptyMaterial());
        var result = provider.resolve(orgId);

        assertThat(result.privateKey()).isNotNull();
        assertThat(result.certificate()).isNotNull();
        var captor = ArgumentCaptor.forClass(SamlConfigEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSpPrivateKeyPem()).startsWith("ENC(");
        assertThat(captor.getValue().getSpCertificatePem()).contains("BEGIN CERTIFICATE");
    }

    @Test
    void throwsWhenRowMissing() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());

        var provider = new SamlSpKeyProvider(repository, encryptionService, emptyMaterial());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> provider.resolve(orgId));
    }

    @Test
    void parseCertificateUtilityHandlesPemInput() throws Exception {
        var generated = generateKeyPair();
        X509Certificate cert = SamlSpKeyProvider.parseCertificate(generated.certificatePem());
        assertThat(cert).isNotNull();
        assertThat(cert.getSubjectX500Principal().getName()).contains("accessflow-test-sp");
    }

    private SamlSpKeyMaterialProperties emptyMaterial() {
        return new SamlSpKeyMaterialProperties(null, null);
    }

    private SamlConfigEntity seeded() {
        var entity = new SamlConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        return entity;
    }

    private GeneratedPair generateKeyPair() throws Exception {
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048, new SecureRandom());
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        var subject = new X500Name("CN=accessflow-test-sp");
        var notBefore = Date.from(Instant.now().minus(1, ChronoUnit.HOURS));
        var notAfter = Date.from(Instant.now().plus(30, ChronoUnit.DAYS));
        var builder = new JcaX509v3CertificateBuilder(subject, BigInteger.ONE, notBefore, notAfter,
                subject, keyPair.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        var holder = builder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);
        return new GeneratedPair(keyPair.getPrivate(),
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                pem("CERTIFICATE", cert.getEncoded()));
    }

    private record GeneratedPair(PrivateKey privateKey, String privateKeyPem, String certificatePem) {
    }

    private static String pem(String type, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }
}
