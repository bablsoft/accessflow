package com.bablsoft.accessflow.security.internal.saml;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.security.internal.persistence.entity.SamlConfigEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.SamlConfigRepository;
import com.bablsoft.accessflow.security.internal.saml.events.SamlConfigUpdatedEvent;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicRelyingPartyRegistrationRepositoryTest {

    @Mock SamlConfigRepository samlConfigRepository;
    @Mock CredentialEncryptionService encryptionService;
    @Mock SamlSpKeyProvider spKeyProvider;
    @Mock OrganizationLookupService organizationLookupService;

    private final UUID orgId = UUID.randomUUID();
    private HttpServer metadataServer;
    private String metadataUrl;
    private GeneratedKeyPair idpKey;
    private GeneratedKeyPair spKey;

    @BeforeEach
    void startMetadataServer() throws Exception {
        idpKey = generateKeyPair("CN=accessflow-test-idp");
        spKey = generateKeyPair("CN=accessflow-test-sp");
        var metadataXml = idpMetadataXml(idpKey.certificate(), "https://idp.example.com",
                "https://idp.example.com/sso");
        metadataServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        metadataServer.createContext("/metadata", exchange -> {
            var body = metadataXml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/samlmetadata+xml");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        metadataServer.start();
        metadataUrl = "http://127.0.0.1:" + metadataServer.getAddress().getPort() + "/metadata";
    }

    @AfterEach
    void stopMetadataServer() {
        if (metadataServer != null) {
            metadataServer.stop(0);
        }
    }

    @Test
    void unknownRegistrationIdReturnsNull() {
        var repo = newRepo();

        assertThat(repo.findByRegistrationId("other")).isNull();
    }

    @Test
    void returnsNullWhenNoActiveConfig() {
        lenient().when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        lenient().when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.empty());

        assertThat(newRepo().findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID))
                .isNull();
    }

    @Test
    void returnsNullWhenConfigPresentButInactive() {
        var entity = baseEntity();
        entity.setActive(false);
        lenient().when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        lenient().when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));

        assertThat(newRepo().findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID))
                .isNull();
    }

    @Test
    void returnsNullWhenActiveConfigMissesIdpMetadataUrl() {
        var entity = baseEntity();
        entity.setActive(true);
        entity.setIdpMetadataUrl(null);
        lenient().when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        lenient().when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));

        assertThat(newRepo().findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID))
                .isNull();
    }

    @Test
    void returnsNullWhenIdpMetadataUrlIsBlank() {
        var entity = baseEntity();
        entity.setActive(true);
        entity.setIdpMetadataUrl("   ");
        lenient().when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        lenient().when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));

        assertThat(newRepo().findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID))
                .isNull();
    }

    @Test
    void returnsNullWhenActiveConfigMissesIdpCert() {
        var entity = baseEntity();
        entity.setActive(true);
        entity.setIdpMetadataUrl(metadataUrl);
        entity.setSigningCertPem(null);
        lenient().when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        lenient().when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));

        assertThat(newRepo().findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID))
                .isNull();
    }

    @Test
    void returnsNullWhenIdpCertIsBlank() {
        var entity = baseEntity();
        entity.setActive(true);
        entity.setIdpMetadataUrl(metadataUrl);
        entity.setSigningCertPem("   ");
        lenient().when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        lenient().when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));

        assertThat(newRepo().findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID))
                .isNull();
    }

    @Test
    void buildsRegistrationFromActiveConfigAndCachesIt() {
        var entity = baseEntity();
        entity.setActive(true);
        entity.setIdpMetadataUrl(metadataUrl);
        entity.setIdpEntityId("https://idp.example.com");
        entity.setSpEntityId("https://accessflow.example.com/saml");
        entity.setAcsUrl("https://accessflow.example.com/api/v1/auth/saml/acs");
        entity.setSigningCertPem("ENC(cert)");
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("ENC(cert)")).thenReturn(idpKey.certificatePem());
        when(spKeyProvider.resolve(orgId)).thenReturn(new SamlSpKeyProvider.SpKeyPair(
                spKey.keyPair().getPrivate(), spKey.certificate(),
                spKey.privateKeyPem(), spKey.certificatePem()));

        var repo = newRepo();
        var first = repo.findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID);

        assertThat(first).isNotNull();
        assertThat(first.getRegistrationId())
                .isEqualTo(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID);
        assertThat(first.getEntityId()).isEqualTo("https://accessflow.example.com/saml");
        assertThat(first.getAssertionConsumerServiceLocation())
                .isEqualTo("https://accessflow.example.com/api/v1/auth/saml/acs");
        assertThat(first.getSigningX509Credentials())
                .anySatisfy(c -> assertThat(c.getCertificate()).isEqualTo(spKey.certificate()));
        assertThat(first.getAssertingPartyMetadata().getEntityId())
                .isEqualTo("https://idp.example.com");
        assertThat(first.getAssertingPartyMetadata().getVerificationX509Credentials())
                .anySatisfy(c -> assertThat(c.getCertificate()).isEqualTo(idpKey.certificate()));

        // Second call returns the cached instance — no second metadata fetch, no second DB read.
        var second = repo.findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID);
        assertThat(second).isSameAs(first);
    }

    @Test
    void usesDefaultAcsPlaceholderWhenConfigAcsUrlMissing() {
        var entity = baseEntity();
        entity.setActive(true);
        entity.setIdpMetadataUrl(metadataUrl);
        entity.setAcsUrl(null);
        entity.setSpEntityId(null);
        entity.setIdpEntityId(null);
        entity.setSigningCertPem("ENC(cert)");
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("ENC(cert)")).thenReturn(idpKey.certificatePem());
        when(spKeyProvider.resolve(orgId)).thenReturn(new SamlSpKeyProvider.SpKeyPair(
                spKey.keyPair().getPrivate(), spKey.certificate(),
                spKey.privateKeyPem(), spKey.certificatePem()));

        var registration = newRepo()
                .findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID);

        assertThat(registration).isNotNull();
        assertThat(registration.getAssertionConsumerServiceLocation())
                .isEqualTo("{baseUrl}/api/v1/auth/saml/acs");
        // With no spEntityId override, Spring's default template kicks in.
        assertThat(registration.getEntityId()).contains("{baseUrl}");
    }

    @Test
    void blankNotNullOverridesAreTreatedAsAbsentAndFallToDefaults() {
        // Exercises the "non-null but isBlank()" branch on spEntityId, acsUrl, and idpEntityId —
        // each of which should be ignored in favour of the Spring/metadata defaults rather than
        // overwriting them with an empty string.
        var entity = baseEntity();
        entity.setActive(true);
        entity.setIdpMetadataUrl(metadataUrl);
        entity.setIdpEntityId("   ");
        entity.setSpEntityId("   ");
        entity.setAcsUrl("   ");
        entity.setSigningCertPem("ENC(cert)");
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("ENC(cert)")).thenReturn(idpKey.certificatePem());
        when(spKeyProvider.resolve(orgId)).thenReturn(new SamlSpKeyProvider.SpKeyPair(
                spKey.keyPair().getPrivate(), spKey.certificate(),
                spKey.privateKeyPem(), spKey.certificatePem()));

        var registration = newRepo()
                .findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID);

        assertThat(registration).isNotNull();
        // Blank spEntityId / acsUrl → defaults from Spring's template (contain `{baseUrl}`).
        assertThat(registration.getEntityId()).contains("{baseUrl}");
        assertThat(registration.getAssertionConsumerServiceLocation())
                .isEqualTo("{baseUrl}/api/v1/auth/saml/acs");
        // Blank idpEntityId → fall back to the entityID embedded in the metadata XML.
        assertThat(registration.getAssertingPartyMetadata().getEntityId())
                .isEqualTo("https://idp.example.com");
    }

    @Test
    void samlConfigUpdatedEventEvictsCacheAndForcesRebuild() {
        var entity = baseEntity();
        entity.setActive(true);
        entity.setIdpMetadataUrl(metadataUrl);
        entity.setIdpEntityId("https://idp.example.com");
        entity.setSigningCertPem("ENC(cert)");
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("ENC(cert)")).thenReturn(idpKey.certificatePem());
        when(spKeyProvider.resolve(orgId)).thenReturn(new SamlSpKeyProvider.SpKeyPair(
                spKey.keyPair().getPrivate(), spKey.certificate(),
                spKey.privateKeyPem(), spKey.certificatePem()));

        var repo = newRepo();
        var first = repo.findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID);
        repo.onSamlConfigUpdated(new SamlConfigUpdatedEvent(orgId));
        var second = repo.findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        // Cache evicted — new instance returned.
        assertThat(second).isNotSameAs(first);
    }

    @Test
    void iteratorYieldsBuiltRegistrationWhenActive() {
        var entity = baseEntity();
        entity.setActive(true);
        entity.setIdpMetadataUrl(metadataUrl);
        entity.setIdpEntityId("https://idp.example.com");
        entity.setSigningCertPem("ENC(cert)");
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("ENC(cert)")).thenReturn(idpKey.certificatePem());
        when(spKeyProvider.resolve(orgId)).thenReturn(new SamlSpKeyProvider.SpKeyPair(
                spKey.keyPair().getPrivate(), spKey.certificate(),
                spKey.privateKeyPem(), spKey.certificatePem()));

        var iterator = newRepo().iterator();

        assertThat(iterator.hasNext()).isTrue();
        var registration = iterator.next();
        assertThat(registration.getRegistrationId())
                .isEqualTo(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID);
        assertThat(iterator.hasNext()).isFalse();
    }

    @Test
    void iteratorReturnsEmptyWhenNothingActive() {
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.empty());

        assertThat(newRepo().iterator().hasNext()).isFalse();
    }

    @Test
    void runtimeExceptionDuringBuildIsSwallowedAndReturnsNull() {
        when(organizationLookupService.singleOrganization())
                .thenThrow(new IllegalStateException("No organization has been set up yet"));

        assertThat(newRepo().findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID))
                .isNull();
    }

    @Test
    void samlConfigUpdatedEventOnEmptyCacheIsHarmless() {
        // No throw, no DB call, no side effect.
        newRepo().onSamlConfigUpdated(new SamlConfigUpdatedEvent(orgId));
    }

    private DynamicRelyingPartyRegistrationRepository newRepo() {
        return new DynamicRelyingPartyRegistrationRepository(
                samlConfigRepository, encryptionService, spKeyProvider, organizationLookupService);
    }

    private SamlConfigEntity baseEntity() {
        var entity = new SamlConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        return entity;
    }

    private static GeneratedKeyPair generateKeyPair(String subject) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        var keyPair = kpg.generateKeyPair();
        var x500 = new X500Name(subject);
        var notBefore = Date.from(Instant.now().minus(1, ChronoUnit.HOURS));
        var notAfter = Date.from(Instant.now().plus(30, ChronoUnit.DAYS));
        var builder = new JcaX509v3CertificateBuilder(
                x500, BigInteger.ONE, notBefore, notAfter, x500, keyPair.getPublic());
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        var holder = builder.build(signer);
        var cert = new JcaX509CertificateConverter().getCertificate(holder);
        return new GeneratedKeyPair(keyPair, cert,
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                pem("CERTIFICATE", cert.getEncoded()));
    }

    private static String pem(String type, byte[] der) {
        var b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    /** Build a minimal SAML 2.0 IdP metadata XML document embedding the cert. */
    private static String idpMetadataXml(X509Certificate cert, String entityId, String ssoLocation)
            throws Exception {
        var certBase64 = Base64.getEncoder().encodeToString(cert.getEncoded());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\""
                + " entityID=\"" + entityId + "\">\n"
                + "  <md:IDPSSODescriptor protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\""
                + " WantAuthnRequestsSigned=\"false\">\n"
                + "    <md:KeyDescriptor use=\"signing\">\n"
                + "      <ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">\n"
                + "        <ds:X509Data>\n"
                + "          <ds:X509Certificate>" + certBase64 + "</ds:X509Certificate>\n"
                + "        </ds:X509Data>\n"
                + "      </ds:KeyInfo>\n"
                + "    </md:KeyDescriptor>\n"
                + "    <md:SingleSignOnService"
                + " Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\""
                + " Location=\"" + ssoLocation + "\"/>\n"
                + "  </md:IDPSSODescriptor>\n"
                + "</md:EntityDescriptor>\n";
    }

    private record GeneratedKeyPair(KeyPair keyPair, X509Certificate certificate,
                                    String privateKeyPem, String certificatePem) {
    }
}
