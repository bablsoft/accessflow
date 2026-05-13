package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.bablsoft.accessflow.security.internal.persistence.entity.ApiKeyEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(properties = "accessflow.encryption-key=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
@ImportTestcontainers(TestcontainersConfig.class)
class ApiKeysControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private UserEntity user;
    private UserEntity otherUser;
    private String token;

    @DynamicPropertySource
    static void rsaProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var pk = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pk.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, b -> b.apply(springSecurity()).build());
        apiKeyRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Acme");
        org.setSlug("acme-" + UUID.randomUUID());
        organizationRepository.save(org);

        user = saveUser(org, "alice@example.com");
        otherUser = saveUser(org, "bob@example.com");

        var view = new com.bablsoft.accessflow.core.api.UserView(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                user.getOrganization().getId(), user.isActive(), user.getAuthProvider(),
                user.getPasswordHash(), user.getLastLoginAt(), user.getPreferredLanguage(),
                user.isTotpEnabled(), user.getCreatedAt());
        token = jwtService.generateAccessToken(view);
    }

    private UserEntity saveUser(OrganizationEntity org, String email) {
        var u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(email);
        u.setPasswordHash(passwordEncoder.encode("Password123!"));
        u.setRole(UserRoleType.ANALYST);
        u.setAuthProvider(AuthProviderType.LOCAL);
        u.setActive(true);
        u.setOrganization(org);
        return userRepository.save(u);
    }

    @Test
    void list_returns_empty_when_no_keys() {
        var result = mvc.get().uri("/api/v1/me/api-keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$").asArray().isEmpty();
    }

    @Test
    void create_returns_raw_key_once_then_list_omits_it() {
        var create = mvc.post().uri("/api/v1/me/api-keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ci\"}").exchange();
        assertThat(create).hasStatus(201);
        assertThat(create).bodyJson().extractingPath("$.raw_key").asString().startsWith("af_");
        assertThat(create).bodyJson().extractingPath("$.api_key.name").asString().isEqualTo("ci");
        assertThat(create).bodyJson().extractingPath("$.api_key.key_prefix").asString().startsWith("af_");

        var list = mvc.get().uri("/api/v1/me/api-keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        assertThat(list).hasStatus(200);
        // 'raw_key' is never returned from list — only the prefix is.
        assertThat(list).bodyText().doesNotContain("raw_key");
    }

    @Test
    void duplicate_name_returns_409() {
        mvc.post().uri("/api/v1/me/api-keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ci\"}").exchange();

        var second = mvc.post().uri("/api/v1/me/api-keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ci\"}").exchange();
        assertThat(second).hasStatus(409);
        assertThat(second).bodyJson().extractingPath("$.error").asString().isEqualTo("API_KEY_DUPLICATE_NAME");
    }

    @Test
    void blank_name_returns_400() {
        var result = mvc.post().uri("/api/v1/me/api-keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}").exchange();
        assertThat(result).hasStatus(400);
    }

    @Test
    void revoke_marks_key_revoked_and_is_idempotent() {
        var entity = persistKeyFor(user);

        var first = mvc.delete().uri("/api/v1/me/api-keys/{id}", entity.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        assertThat(first).hasStatus(204);

        var refreshed = apiKeyRepository.findById(entity.getId()).orElseThrow();
        assertThat(refreshed.getRevokedAt()).isNotNull();

        var second = mvc.delete().uri("/api/v1/me/api-keys/{id}", entity.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        assertThat(second).hasStatus(204);
    }

    @Test
    void revoke_returns_404_for_other_users_key() {
        var entity = persistKeyFor(otherUser);
        var result = mvc.delete().uri("/api/v1/me/api-keys/{id}", entity.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        assertThat(result).hasStatus(404);
        // Still active — not actually revoked.
        assertThat(apiKeyRepository.findById(entity.getId()).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    void revoke_returns_404_for_unknown_key() {
        var result = mvc.delete().uri("/api/v1/me/api-keys/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
        assertThat(result).hasStatus(404);
    }

    private ApiKeyEntity persistKeyFor(UserEntity owner) {
        var entity = new ApiKeyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(owner.getOrganization().getId());
        entity.setUserId(owner.getId());
        entity.setName("k-" + UUID.randomUUID());
        entity.setKeyPrefix("af_demoprefx");
        entity.setKeyHash("hash-" + UUID.randomUUID());
        entity.setCreatedAt(Instant.now());
        return apiKeyRepository.save(entity);
    }
}
