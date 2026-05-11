package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.security.api.AuthenticationService;
import com.partqam.accessflow.security.internal.jwt.JwtService;
import com.partqam.accessflow.security.internal.persistence.repo.SamlConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
@TestPropertySource(properties = "accessflow.edition=enterprise")
class AdminSamlConfigControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired SamlConfigRepository repository;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;
    // Enterprise context has no LocalAuthenticationService and SamlAuthenticationService is not yet
    // implemented; supply a stub so AuthController can wire.
    @MockitoBean AuthenticationService authenticationService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity admin;
    private UserEntity analyst;
    private String adminToken;
    private String analystToken;

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(((RSAPrivateCrtKey) kp.getPrivate()).getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        repository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        org.setEdition(EditionType.ENTERPRISE);
        organizationRepository.save(org);

        admin = saveUser("admin@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst@example.com", UserRoleType.ANALYST);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void getReturnsTransientDefaultsBeforeFirstUpdate() {
        var result = mvc.get().uri("/api/v1/admin/saml-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.active").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.attr_email").asString().isEqualTo("email");
        assertThat(result).bodyJson().extractingPath("$.default_role").asString().isEqualTo("ANALYST");
    }

    @Test
    void putPersistsAndEncryptsCertificate() {
        var body = "{\"idp_metadata_url\":\"https://idp.example.com/m\","
                + "\"idp_entity_id\":\"idp\",\"sp_entity_id\":\"sp\","
                + "\"acs_url\":\"https://app.example.com/saml/acs\","
                + "\"signing_cert_pem\":\"-----BEGIN CERTIFICATE-----\\nABC\\n-----END CERTIFICATE-----\","
                + "\"default_role\":\"REVIEWER\",\"active\":true}";

        var put = mvc.put().uri("/api/v1/admin/saml-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(put).hasStatus(200);
        assertThat(put).bodyJson().extractingPath("$.signing_cert_pem").asString().isEqualTo("********");
        assertThat(put).bodyJson().extractingPath("$.default_role").asString().isEqualTo("REVIEWER");
        assertThat(put).bodyJson().extractingPath("$.active").asBoolean().isTrue();

        var stored = repository.findByOrganizationId(org.getId()).orElseThrow();
        assertThat(stored.getSigningCertPem()).isNotNull();
        assertThat(encryptionService.decrypt(stored.getSigningCertPem()))
                .startsWith("-----BEGIN CERTIFICATE-----");
    }

    @Test
    void putWithMaskedCertPreservesExistingCipher() {
        // First write a real cert.
        var firstBody = "{\"signing_cert_pem\":\"REAL\"}";
        mvc.put().uri("/api/v1/admin/saml-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstBody)
                .exchange();
        var originalCipher = repository.findByOrganizationId(org.getId()).orElseThrow().getSigningCertPem();

        var updateBody = "{\"signing_cert_pem\":\"********\",\"active\":true}";
        mvc.put().uri("/api/v1/admin/saml-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody)
                .exchange();

        var afterCipher = repository.findByOrganizationId(org.getId()).orElseThrow().getSigningCertPem();
        assertThat(afterCipher).isEqualTo(originalCipher);
    }

    @Test
    void analystForbidden() {
        var get = mvc.get().uri("/api/v1/admin/saml-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(get).hasStatus(403);
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(role.name());
        user.setPasswordHash("hashed");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private String generateToken(UserEntity entity) {
        var view = new UserView(
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
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
