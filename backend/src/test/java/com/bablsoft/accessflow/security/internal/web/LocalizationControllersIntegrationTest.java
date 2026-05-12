package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.LocalizationConfigRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
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
class LocalizationControllersIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired LocalizationConfigRepository localizationConfigRepository;
    @Autowired JwtService jwtService;

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
        localizationConfigRepository.deleteAll();
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

    @Test
    void adminGetReturnsTransientEnglishDefaultBeforeAnyUpdate() {
        var result = mvc.get().uri("/api/v1/admin/localization-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.default_language").asString().isEqualTo("en");
        assertThat(result).bodyJson().extractingPath("$.ai_review_language").asString().isEqualTo("en");
        assertThat(result).bodyJson().extractingPath("$.available_languages[0]").asString().isEqualTo("en");
    }

    @Test
    void adminPutPersistsConfig() {
        var body = "{\"available_languages\":[\"en\",\"es\",\"fr\"],"
                + "\"default_language\":\"en\",\"ai_review_language\":\"es\"}";

        var put = mvc.put().uri("/api/v1/admin/localization-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(put).hasStatus(200);

        var get = mvc.get().uri("/api/v1/admin/localization-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(get).bodyJson().extractingPath("$.ai_review_language").asString().isEqualTo("es");

        var stored = localizationConfigRepository.findByOrganizationId(org.getId()).orElseThrow();
        assertThat(stored.getAvailableLanguages()).containsExactly("en", "es", "fr");
        assertThat(stored.getDefaultLanguage()).isEqualTo("en");
        assertThat(stored.getAiReviewLanguage()).isEqualTo("es");
    }

    @Test
    void adminPutRejectsUnsupportedLanguage() {
        var body = "{\"available_languages\":[\"en\",\"xx\"],"
                + "\"default_language\":\"en\",\"ai_review_language\":\"en\"}";
        var result = mvc.put().uri("/api/v1/admin/localization-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(result).hasStatus(400);
    }

    @Test
    void adminPutRejectsDefaultNotInAvailable() {
        var body = "{\"available_languages\":[\"en\"],"
                + "\"default_language\":\"es\",\"ai_review_language\":\"en\"}";
        var result = mvc.put().uri("/api/v1/admin/localization-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(result).hasStatus(400);
    }

    @Test
    void adminPutRejectsEmptyAvailableLanguages() {
        var body = "{\"available_languages\":[],"
                + "\"default_language\":\"en\",\"ai_review_language\":\"en\"}";
        var result = mvc.put().uri("/api/v1/admin/localization-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(result).hasStatus(400);
    }

    @Test
    void analystForbiddenFromAdminEndpoints() {
        var get = mvc.get().uri("/api/v1/admin/localization-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(get).hasStatus(403);
    }

    @Test
    void meGetReturnsAvailableLanguagesAndDefault() {
        configureOrg("en", "es");

        var result = mvc.get().uri("/api/v1/me/localization")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.default_language").asString().isEqualTo("en");
        assertThat(result).bodyJson().extractingPath("$.current_language").asString().isEqualTo("en");
    }

    @Test
    void mePutPersistsAllowedLanguage() {
        configureOrg("en", "es");

        var put = mvc.put().uri("/api/v1/me/localization")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"language\":\"es\"}")
                .exchange();

        assertThat(put).hasStatus(200);
        assertThat(put).bodyJson().extractingPath("$.current_language").asString().isEqualTo("es");
        var refreshed = userRepository.findById(analyst.getId()).orElseThrow();
        assertThat(refreshed.getPreferredLanguage()).isEqualTo("es");
    }

    @Test
    void mePutRejectsLanguageNotInAllowList() {
        configureOrg("en");

        var put = mvc.put().uri("/api/v1/me/localization")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"language\":\"es\"}")
                .exchange();

        assertThat(put).hasStatus(400);
    }

    @Test
    void mePutRejectsUnsupportedLanguage() {
        configureOrg("en", "es");

        var put = mvc.put().uri("/api/v1/me/localization")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"language\":\"xx\"}")
                .exchange();

        assertThat(put).hasStatus(400);
    }

    private void configureOrg(String... languages) {
        var available = String.join("\",\"", languages);
        var body = "{\"available_languages\":[\"" + available
                + "\"],\"default_language\":\"" + languages[0]
                + "\",\"ai_review_language\":\"" + languages[0] + "\"}";
        var put = mvc.put().uri("/api/v1/admin/localization-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(put).hasStatus(200);
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
