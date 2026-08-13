package com.bablsoft.accessflow.scim.internal.web.admin;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.scim.internal.persistence.repo.ScimConfigRepository;
import com.bablsoft.accessflow.scim.internal.persistence.repo.ScimTokenRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
class ScimAdminControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired ScimConfigRepository scimConfigRepository;
    @Autowired ScimTokenRepository scimTokenRepository;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
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
        scimTokenRepository.deleteAll();
        scimConfigRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(org);

        adminToken = generateToken(saveUser("admin@example.com", UserRoleType.ADMIN));
        analystToken = generateToken(saveUser("analyst@example.com", UserRoleType.ANALYST));
    }

    @Test
    void getReturnsDefaultsBeforeFirstUpdate() {
        var result = mvc.get().uri("/api/v1/admin/scim-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.enabled").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.attr_email").asString()
                .isEqualTo("userName");
        assertThat(result).bodyJson().extractingPath("$.attr_display_name").asString()
                .isEqualTo("displayName");
        assertThat(result).bodyJson().extractingPath("$.default_role").asString()
                .isEqualTo("ANALYST");
    }

    @Test
    void putUpsertsTheSingletonRow() {
        var result = mvc.put().uri("/api/v1/admin/scim-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"enabled":true,"attr_email":"emails.primary",
                         "attr_display_name":"name.formatted","default_role":"READONLY"}
                        """)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.enabled").asBoolean().isTrue();
        var stored = scimConfigRepository.findByOrganizationId(org.getId()).orElseThrow();
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getAttrEmail()).isEqualTo("emails.primary");
        assertThat(stored.getDefaultRole()).isEqualTo(UserRoleType.READONLY);
    }

    @Test
    void putRejectsUnknownMappingValue() {
        var result = mvc.put().uri("/api/v1/admin/scim-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attr_email\":\"nickName\"}")
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(scimConfigRepository.findByOrganizationId(org.getId())).isEmpty();
    }

    @Test
    void tokenLifecycleShowsRawValueExactlyOnce() throws Exception {
        var created = mvc.post().uri("/api/v1/admin/scim/tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"okta-prod\"}")
                .exchange();

        assertThat(created).hasStatus(201);
        assertThat(created).bodyJson().extractingPath("$.raw_token").asString()
                .startsWith("af_scim_");
        assertThat(created).bodyJson().extractingPath("$.token.name").asString()
                .isEqualTo("okta-prod");

        var list = mvc.get().uri("/api/v1/admin/scim/tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(list).hasStatus(200);
        assertThat(list.getResponse().getContentAsString()).doesNotContain("raw_token");
        assertThat(list).bodyJson().extractingPath("$[0].token_prefix").asString()
                .startsWith("af_scim_");

        // The stored row carries only the SHA-256 hash.
        var stored = scimTokenRepository
                .findAllByOrganizationIdOrderByCreatedAtDesc(org.getId()).get(0);
        assertThat(stored.getTokenHash()).hasSize(64).doesNotStartWith("af_scim_");
    }

    @Test
    void duplicateTokenNameReturns409() {
        mvc.post().uri("/api/v1/admin/scim/tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup\"}")
                .exchange();

        var second = mvc.post().uri("/api/v1/admin/scim/tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup\"}")
                .exchange();

        assertThat(second).hasStatus(409);
        assertThat(second).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SCIM_TOKEN_NAME_CONFLICT");
    }

    @Test
    void revokeReturns204AndUnknownTokenIs404() {
        mvc.post().uri("/api/v1/admin/scim/tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"to-revoke\"}")
                .exchange();
        var tokenId = scimTokenRepository
                .findAllByOrganizationIdOrderByCreatedAtDesc(org.getId()).get(0).getId();

        var revoked = mvc.delete().uri("/api/v1/admin/scim/tokens/" + tokenId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(revoked).hasStatus(204);
        assertThat(scimTokenRepository.findById(tokenId).orElseThrow().getRevokedAt()).isNotNull();

        var missing = mvc.delete().uri("/api/v1/admin/scim/tokens/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(missing).hasStatus(404);
        assertThat(missing).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SCIM_TOKEN_NOT_FOUND");
    }

    @Test
    void analystForbiddenEverywhere() {
        assertThat(mvc.get().uri("/api/v1/admin/scim-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()).hasStatus(403);
        assertThat(mvc.get().uri("/api/v1/admin/scim/tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()).hasStatus(403);
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
