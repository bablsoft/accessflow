package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The API-call decision trace end to end (AF-967) — the permission gate, org scoping, the audit row,
 * and above all the read-only guarantee: a simulation must leave no {@code api_requests} row behind
 * and must never contact the governed API.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminApiCallSimulationControllerIntegrationTest {

    private static final String BASE = "/api/v1/admin/api-call-simulations";

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApiConnectorRepository connectorRepository;
    @Autowired ApiConnectorUserPermissionRepository permissionRepository;
    @Autowired ApiRequestRepository requestRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private ApiConnectorEntity connector;
    private UserEntity admin;
    private UserEntity analyst;
    private UserEntity reviewer;
    private String adminToken;
    private String analystToken;
    private String reviewerToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        org = saveOrg("ApiTrace " + suffix, "api-trace-" + suffix);
        admin = saveUser("admin-apisim-" + suffix + "@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst-apisim-" + suffix + "@example.com", UserRoleType.ANALYST);
        reviewer = saveUser("reviewer-apisim-" + suffix + "@example.com", UserRoleType.REVIEWER);
        connector = saveConnector("billing-api-" + suffix);
        givenPermission(analyst, true, true);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
        reviewerToken = generateToken(reviewer);
    }

    /** Scoped to this class's own rows — the Testcontainers database is shared. */
    @AfterEach
    void cleanup() {
        if (connector == null) {
            return;
        }
        jdbcTemplate.update("delete from audit_log where organization_id = ?", org.getId());
        permissionRepository.deleteAll(permissionRepository.findByConnectorId(connector.getId()));
        connectorRepository.deleteById(connector.getId());
        userRepository.deleteAllById(List.of(admin.getId(), analyst.getId(), reviewer.getId()));
        organizationRepository.deleteById(org.getId());
        connector = null;
    }

    private String body(UUID userId, UUID connectorId) {
        return """
                {"user_id":"%s","connector_id":"%s","verb":"DELETE","ai_outcome":"SKIPPED"}
                """.formatted(userId, connectorId);
    }

    @Test
    void anAdminGetsTheFullOrderedTrace() {
        var result = mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), connector.getId()))
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.steps.length()").asNumber().isEqualTo(9);
        assertThat(result).bodyJson().extractingPath("$.steps[0].step").asString()
                .isEqualTo("CONNECTOR_GATES");
        assertThat(result).bodyJson().extractingPath("$.steps[8].step").asString()
                .isEqualTo("BREAK_GLASS");
        assertThat(result).bodyJson().extractingPath("$.resulting_status").asString()
                .isEqualTo("PENDING_REVIEW");
        // Every step carries a resolved sentence, not a raw message key.
        assertThat(result).bodyJson().extractingPath("$.steps[0].reason").asString()
                .doesNotContain("apigov.simulation");
        assertThat(result).bodyJson().extractingPath("$.caveats").asArray()
                .contains("RESPONSE_SHAPE_ABSENT");
    }

    @Test
    void aSimulationCreatesNoApiRequestRow() {
        long before = requestRepository.count();

        mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), connector.getId()))
                .exchange();

        assertThat(requestRepository.count()).isEqualTo(before);
    }

    @Test
    void everySimulationWritesExactlyOneAuditRowAgainstTheConnector() {
        mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), connector.getId()))
                .exchange();

        var rows = jdbcTemplate.queryForList(
                "select resource_type, resource_id, metadata::text from audit_log "
                        + "where organization_id = ? and action = ?",
                org.getId(), AuditAction.ACCESS_SIMULATION_RUN.name());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("resource_type")).hasToString("api_connector");
        assertThat(rows.get(0).get("resource_id")).hasToString(connector.getId().toString());
        assertThat(String.valueOf(rows.get(0).get("metadata")))
                .contains(analyst.getId().toString())
                .contains("PENDING_REVIEW");
    }

    @Test
    void theTraceExplainsAPermissionDenialAndStillReportsEveryStage() {
        var result = mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(reviewer.getId(), connector.getId()))
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.steps.length()").asNumber().isEqualTo(9);
        assertThat(result).bodyJson().extractingPath("$.steps[3].step").asString()
                .isEqualTo("OPERATION_PERMISSION");
        assertThat(result).bodyJson().extractingPath("$.steps[3].outcome").asString()
                .isEqualTo("DENY");
        assertThat(result).bodyJson().extractingPath("$.steps[4].outcome").asString()
                .isEqualTo("SKIP");
        // Refused before a status was ever assigned, so the field is absent rather than null.
        assertThat(result).bodyJson().doesNotHavePath("$.resulting_status");
    }

    @Test
    void anAnalystAndAReviewerAreForbidden() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), connector.getId()))
                .exchange()).hasStatus(403);
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), connector.getId()))
                .exchange()).hasStatus(403);
    }

    @Test
    void anonymousCallersAreRejected() {
        assertThat(mvc.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), connector.getId()))
                .exchange()).hasStatus(401);
    }

    @Test
    void anUnknownConnectorIsNotFound() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), UUID.randomUUID()))
                .exchange()).hasStatus(404);
    }

    @Test
    void anUnknownUserIsNotFound() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(UUID.randomUUID(), connector.getId()))
                .exchange()).hasStatus(404);
    }

    @Test
    void aMissingConnectorIdIsAValidationError() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s"}
                        """.formatted(analyst.getId()))
                .exchange()).hasStatus(400);
    }

    @Test
    void aMalformedAiOutcomeIsAClientErrorNotAServerOne() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s","connector_id":"%s","ai_outcome":"NOPE"}
                        """.formatted(analyst.getId(), connector.getId()))
                .exchange()).hasStatus(400);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private OrganizationEntity saveOrg(String name, String slug) {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setSlug(slug);
        return organizationRepository.save(entity);
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setEmail(email);
        entity.setDisplayName(email);
        entity.setPasswordHash("x");
        entity.setRole(role);
        entity.setAuthProvider(AuthProviderType.LOCAL);
        entity.setActive(true);
        entity.setOrganization(org);
        return userRepository.save(entity);
    }

    private ApiConnectorEntity saveConnector(String name) {
        var entity = new ApiConnectorEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setName(name);
        entity.setProtocol(ApiProtocol.REST);
        entity.setBaseUrl("https://api.invalid");
        entity.setAuthMethod(ApiAuthMethod.NONE);
        entity.setActive(true);
        entity.setRequireReviewReads(false);
        entity.setRequireReviewWrites(true);
        return connectorRepository.save(entity);
    }

    private void givenPermission(UserEntity user, boolean canRead, boolean canWrite) {
        var entity = new ApiConnectorUserPermissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setConnectorId(connector.getId());
        entity.setUserId(user.getId());
        entity.setCanRead(canRead);
        entity.setCanWrite(canWrite);
        entity.setCreatedBy(admin.getId());
        permissionRepository.save(entity);
    }

    private String generateToken(UserEntity entity) {
        var view = new com.bablsoft.accessflow.core.api.UserView(
                entity.getId(), entity.getEmail(), entity.getDisplayName(), entity.getRole(),
                entity.getOrganization().getId(), entity.isActive(), entity.getAuthProvider(),
                entity.getPasswordHash(), entity.getLastLoginAt(), entity.getPreferredLanguage(),
                entity.isTotpEnabled(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
