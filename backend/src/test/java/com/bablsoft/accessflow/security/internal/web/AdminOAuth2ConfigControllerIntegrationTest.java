package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.api.AuthenticationService;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.bablsoft.accessflow.security.internal.persistence.repo.OAuth2ConfigRepository;
import com.bablsoft.accessflow.security.internal.persistence.repo.SamlConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class AdminOAuth2ConfigControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired OAuth2ConfigRepository repository;
    @Autowired SamlConfigRepository samlConfigRepository;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;
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
        registry.add("accessflow.audit.hmac-key", () ->
                "abababababababababababababababababababababababababababababababab");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        repository.deleteAll();
        samlConfigRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(org);

        admin = saveUser("admin@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst@example.com", UserRoleType.ANALYST);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @AfterEach
    void cleanup() {
        repository.deleteAll();
        samlConfigRepository.deleteAll();
    }

    @Test
    void listReturnsAllFourProvidersAsDefaultsWhenEmpty() {
        var result = mvc.get().uri("/api/v1/admin/oauth2-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.length()").asNumber()
                .isEqualTo(OAuth2ProviderType.values().length);
        assertThat(result).bodyJson().extractingPath("$[0].active").asBoolean().isFalse();
    }

    @Test
    void getReturnsTransientDefaultForUnknownProvider() {
        var result = mvc.get().uri("/api/v1/admin/oauth2-config/GOOGLE")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.provider").asString().isEqualTo("GOOGLE");
        assertThat(result).bodyJson().extractingPath("$.active").asBoolean().isFalse();
    }

    @Test
    void putPersistsAndEncryptsClientSecret() {
        var body = "{\"client_id\":\"client-abc\",\"client_secret\":\"my-secret\","
                + "\"default_role\":\"REVIEWER\",\"active\":true}";

        var put = mvc.put().uri("/api/v1/admin/oauth2-config/GOOGLE")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(put).hasStatus(200);
        assertThat(put).bodyJson().extractingPath("$.client_id").asString().isEqualTo("client-abc");
        assertThat(put).bodyJson().extractingPath("$.client_secret").asString().isEqualTo("********");
        assertThat(put).bodyJson().extractingPath("$.active").asBoolean().isTrue();

        var stored = repository.findByOrganizationIdAndProvider(org.getId(),
                OAuth2ProviderType.GOOGLE).orElseThrow();
        assertThat(encryptionService.decrypt(stored.getClientSecretEncrypted())).isEqualTo("my-secret");
    }

    @Test
    void putWithMaskedSecretPreservesExistingCipher() {
        var firstBody = "{\"client_id\":\"c\",\"client_secret\":\"REAL\",\"default_role\":\"ANALYST\",\"active\":true}";
        mvc.put().uri("/api/v1/admin/oauth2-config/GITHUB")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstBody)
                .exchange();
        var originalCipher = repository.findByOrganizationIdAndProvider(org.getId(),
                OAuth2ProviderType.GITHUB).orElseThrow().getClientSecretEncrypted();

        var updateBody = "{\"client_secret\":\"********\",\"default_role\":\"ANALYST\",\"active\":true}";
        mvc.put().uri("/api/v1/admin/oauth2-config/GITHUB")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody)
                .exchange();

        var after = repository.findByOrganizationIdAndProvider(org.getId(),
                OAuth2ProviderType.GITHUB).orElseThrow().getClientSecretEncrypted();
        assertThat(after).isEqualTo(originalCipher);
    }

    @Test
    void putWithActivationButNoSecretReturns422() {
        var body = "{\"client_id\":\"c\",\"default_role\":\"ANALYST\",\"active\":true}";

        var put = mvc.put().uri("/api/v1/admin/oauth2-config/GOOGLE")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(put).hasStatus(422);
        assertThat(put).bodyJson().extractingPath("$.error").asString().isEqualTo("OAUTH2_CONFIG_INVALID");
    }

    @Test
    void deleteRemovesRow() {
        var firstBody = "{\"client_id\":\"c\",\"client_secret\":\"s\",\"default_role\":\"ANALYST\",\"active\":true}";
        mvc.put().uri("/api/v1/admin/oauth2-config/GITLAB")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstBody)
                .exchange();

        var del = mvc.delete().uri("/api/v1/admin/oauth2-config/GITLAB")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(del).hasStatus(204);
        assertThat(repository.findByOrganizationIdAndProvider(org.getId(),
                OAuth2ProviderType.GITLAB)).isEmpty();
    }

    @Test
    void analystForbidden() {
        var get = mvc.get().uri("/api/v1/admin/oauth2-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(get).hasStatus(403);
    }

    @Test
    void publicProvidersListReturnsActiveOnlyWithNoAuth() {
        // No config yet — empty list.
        var emptyList = mvc.get().uri("/api/v1/auth/oauth2/providers").exchange();
        assertThat(emptyList).hasStatus(200);
        assertThat(emptyList).bodyJson().extractingPath("$.length()").asNumber().isEqualTo(0);

        // Activate Google.
        mvc.put().uri("/api/v1/admin/oauth2-config/GOOGLE")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"client_id\":\"c\",\"client_secret\":\"s\",\"default_role\":\"ANALYST\",\"active\":true}")
                .exchange();

        var withGoogle = mvc.get().uri("/api/v1/auth/oauth2/providers").exchange();
        assertThat(withGoogle).hasStatus(200);
        assertThat(withGoogle).bodyJson().extractingPath("$[0].provider").asString().isEqualTo("GOOGLE");
        assertThat(withGoogle).bodyJson().extractingPath("$[0].display_name").asString().isEqualTo("Google");
    }

    @Test
    void publicSamlEnabledReturnsFalseWhenNoConfigExists() {
        var result = mvc.get().uri("/api/v1/auth/saml/enabled").exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.enabled").asBoolean().isFalse();
    }

    @Test
    void publicSamlEnabledFlipsAfterAdminActivatesSamlConfig() {
        mvc.put().uri("/api/v1/admin/saml-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idp_metadata_url\":\"https://idp.example.com/m\","
                        + "\"idp_entity_id\":\"idp\",\"sp_entity_id\":\"sp\","
                        + "\"acs_url\":\"https://app.example.com/saml/acs\","
                        + "\"default_role\":\"ANALYST\",\"active\":true}")
                .exchange();

        var result = mvc.get().uri("/api/v1/auth/saml/enabled").exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.enabled").asBoolean().isTrue();
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
