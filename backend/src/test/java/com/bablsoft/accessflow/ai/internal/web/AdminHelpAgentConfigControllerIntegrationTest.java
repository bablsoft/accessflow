package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The admin help-agent surface end to end: {@code AI_MANAGE} gates all four endpoints, and an
 * organization that has never saved a row is served defaults rather than a 404.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminHelpAgentConfigControllerIntegrationTest {

    private static final String PATH = "/api/v1/admin/help-agent";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired HelpAgentConfigRepository repository;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private UUID organizationId;
    private String adminToken;
    private String analystToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Help Agent Web Org " + UUID.randomUUID());
        org.setSlug("help-agent-web-" + UUID.randomUUID());
        organizationRepository.save(org);
        organizationId = org.getId();
        repository.findByOrganizationId(organizationId).ifPresent(repository::delete);
        adminToken = generateToken(saveUser(org, UserRoleType.ADMIN));
        analystToken = generateToken(saveUser(org, UserRoleType.ANALYST));
    }

    @Test
    void getReturnsDefaultsForAnOrganizationWithNoRow() {
        var result = mvc.get().uri(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        // Null fields are omitted from the response, so "no row yet" reads as an absent id.
        assertThat(result).bodyJson().doesNotHavePath("$.id");
        assertThat(result).bodyJson().extractingPath("$.enabled").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.retrieval_enabled").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.top_k").asNumber().isEqualTo(6);
        assertThat(result).bodyJson().extractingPath("$.retention_days").asNumber().isEqualTo(90);
    }

    @Test
    void getIsForbiddenWithoutAiManage() {
        assertThat(mvc.get().uri(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()).hasStatus(403);
    }

    @Test
    void putPersistsTunablesAndIsReadBack() {
        var put = mvc.put().uri(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"retention_days\":30,\"top_k\":9,\"send_user_context\":false}")
                .exchange();

        assertThat(put).hasStatus(200);
        assertThat(put).bodyJson().extractingPath("$.retention_days").asNumber().isEqualTo(30);
        assertThat(put).bodyJson().extractingPath("$.top_k").asNumber().isEqualTo(9);
        assertThat(put).bodyJson().extractingPath("$.send_user_context").asBoolean().isFalse();

        var get = mvc.get().uri(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(get).bodyJson().extractingPath("$.id").isNotNull();
        assertThat(get).bodyJson().extractingPath("$.retention_days").asNumber().isEqualTo(30);
    }

    @Test
    void putRejectsAnOutOfRangeTunable() {
        var result = mvc.put().uri(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"top_k\":99}")
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void putRefusesToEnableWithoutABoundAiConfig() {
        var result = mvc.put().uri(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("HELP_AGENT_CONFIG_INVALID");
    }

    @Test
    void putRejectsAnUnknownAiConfig() {
        var result = mvc.put().uri(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"ai_config_id\":\"" + UUID.randomUUID() + "\"}")
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("AI_CONFIG_NOT_FOUND");
    }

    @Test
    void putIsForbiddenWithoutAiManage() {
        assertThat(mvc.put().uri(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"retention_days\":30}")
                .exchange()).hasStatus(403);
    }

    @Test
    void testReportsNotConfiguredForAFreshOrganization() {
        var result = mvc.post().uri(PATH + "/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.status").asString().isEqualTo("ERROR");
    }

    @Test
    void testIsForbiddenWithoutAiManage() {
        assertThat(mvc.post().uri(PATH + "/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()).hasStatus(403);
    }

    @Test
    void reindexIsAccepted() {
        assertThat(mvc.post().uri(PATH + "/reindex")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()).hasStatus(202);
    }

    @Test
    void reindexIsForbiddenWithoutAiManage() {
        assertThat(mvc.post().uri(PATH + "/reindex")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange()).hasStatus(403);
    }

    private UserEntity saveUser(OrganizationEntity org, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com");
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
