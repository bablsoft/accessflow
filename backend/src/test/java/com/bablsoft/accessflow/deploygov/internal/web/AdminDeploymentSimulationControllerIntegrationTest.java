package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentFreezeWindowEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineUserPermissionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentFreezeWindowRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineUserPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The deployment decision trace end to end (AF-967) — the permission gate, the freeze window at a
 * hypothetical instant, the audit row, and the read-only guarantee: a simulation must leave no
 * {@code deployment_requests} row behind.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminDeploymentSimulationControllerIntegrationTest {

    private static final String BASE = "/api/v1/admin/deployment-simulations";

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DeploymentPipelineRepository pipelineRepository;
    @Autowired DeploymentEnvironmentRepository environmentRepository;
    @Autowired DeploymentPipelineUserPermissionRepository permissionRepository;
    @Autowired DeploymentFreezeWindowRepository freezeWindowRepository;
    @Autowired DeploymentRequestRepository requestRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private DeploymentPipelineEntity pipeline;
    private DeploymentEnvironmentEntity environment;
    private UserEntity admin;
    private UserEntity engineer;
    private UserEntity reviewer;
    private String adminToken;
    private String engineerToken;
    private String reviewerToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        org = saveOrg("DeployTrace " + suffix, "deploy-trace-" + suffix);
        admin = saveUser("admin-depsim-" + suffix + "@example.com", UserRoleType.ADMIN);
        engineer = saveUser("engineer-depsim-" + suffix + "@example.com", UserRoleType.ANALYST);
        reviewer = saveUser("reviewer-depsim-" + suffix + "@example.com", UserRoleType.REVIEWER);
        pipeline = savePipeline("checkout-" + suffix);
        environment = saveEnvironment("production");
        givenTriggerGrant(engineer);
        adminToken = generateToken(admin);
        engineerToken = generateToken(engineer);
        reviewerToken = generateToken(reviewer);
    }

    /** Scoped to this class's own rows — the Testcontainers database is shared. */
    @AfterEach
    void cleanup() {
        if (pipeline == null) {
            return;
        }
        jdbcTemplate.update("delete from audit_log where organization_id = ?", org.getId());
        freezeWindowRepository.deleteAll(
                freezeWindowRepository.findByOrganizationIdAndEnabledTrue(org.getId()));
        permissionRepository.deleteAll(permissionRepository.findByPipelineId(pipeline.getId()));
        environmentRepository.deleteById(environment.getId());
        pipelineRepository.deleteById(pipeline.getId());
        userRepository.deleteAllById(List.of(admin.getId(), engineer.getId(), reviewer.getId()));
        organizationRepository.deleteById(org.getId());
        pipeline = null;
    }

    private String body(UUID userId, UUID pipelineId, UUID environmentId) {
        return """
                {"user_id":"%s","pipeline_id":"%s","environment_id":"%s","version":"2.6.0",
                 "ai_outcome":"SKIPPED"}
                """.formatted(userId, pipelineId, environmentId);
    }

    @Test
    void anAdminGetsTheFullOrderedTraceAndTheGateVerdict() {
        var result = mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(engineer.getId(), pipeline.getId(), environment.getId()))
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.steps.length()").asNumber().isEqualTo(9);
        assertThat(result).bodyJson().extractingPath("$.steps[0].step").asString()
                .isEqualTo("PIPELINE_GATES");
        assertThat(result).bodyJson().extractingPath("$.steps[7].step").asString()
                .isEqualTo("GATE_RELEASABILITY");
        assertThat(result).bodyJson().extractingPath("$.steps[8].step").asString()
                .isEqualTo("BREAK_GLASS");
        assertThat(result).bodyJson().extractingPath("$.resulting_status").asString()
                .isEqualTo("PENDING_REVIEW");
        // A deployment still in review is never releasable — the whole point of the gate stage.
        assertThat(result).bodyJson().extractingPath("$.releasable").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.evaluated_at").asString().isNotEmpty();
        assertThat(result).bodyJson().extractingPath("$.steps[0].reason").asString()
                .doesNotContain("deploygov.simulation");
    }

    @Test
    void aSimulationCreatesNoDeploymentRequestRow() {
        long before = requestRepository.count();

        mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(engineer.getId(), pipeline.getId(), environment.getId()))
                .exchange();

        assertThat(requestRepository.count()).isEqualTo(before);
    }

    @Test
    void everySimulationWritesExactlyOneAuditRowAgainstThePipeline() {
        mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(engineer.getId(), pipeline.getId(), environment.getId()))
                .exchange();

        var rows = jdbcTemplate.queryForList(
                "select resource_type, resource_id, metadata::text from audit_log "
                        + "where organization_id = ? and action = ?",
                org.getId(), AuditAction.ACCESS_SIMULATION_RUN.name());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("resource_type")).hasToString("deployment_pipeline");
        assertThat(rows.get(0).get("resource_id")).hasToString(pipeline.getId().toString());
        assertThat(String.valueOf(rows.get(0).get("metadata")))
                .contains(engineer.getId().toString())
                .contains(environment.getId().toString())
                .contains("PENDING_REVIEW");
    }

    @Test
    void aHypotheticalInstantInsideAFreezeWindowIsReportedAsHeld() {
        // The question no admin screen answers: would a release next Saturday morning be frozen?
        var start = Instant.parse("2026-12-19T00:00:00Z");
        var end = Instant.parse("2027-01-05T00:00:00Z");
        saveFreezeWindow(start, end);

        var result = mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s","pipeline_id":"%s","environment_id":"%s","version":"2.6.0",
                         "ai_outcome":"SKIPPED","at":"2026-12-24T09:00:00Z"}
                        """.formatted(engineer.getId(), pipeline.getId(), environment.getId()))
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.steps[2].step").asString()
                .isEqualTo("FREEZE_WINDOW");
        assertThat(result).bodyJson().extractingPath("$.steps[2].outcome").asString()
                .isEqualTo("MATCH");
        assertThat(result).bodyJson().extractingPath("$.steps[2].details.behavior").asString()
                .isEqualTo("HOLD");
        assertThat(result).bodyJson().extractingPath("$.steps[2].details.scope").asString()
                .isEqualTo("ORGANIZATION");
        assertThat(result).bodyJson().extractingPath("$.releasable").asBoolean().isFalse();
        assertThat(result).bodyJson().extractingPath("$.evaluated_at").asString()
                .startsWith("2026-12-24T09:00:00");
    }

    @Test
    void aUserWithoutATriggerGrantIsRefusedAndEveryStageIsStillReported() {
        var result = mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(reviewer.getId(), pipeline.getId(), environment.getId()))
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.steps.length()").asNumber().isEqualTo(9);
        assertThat(result).bodyJson().extractingPath("$.steps[1].step").asString()
                .isEqualTo("TRIGGER_PERMISSION");
        assertThat(result).bodyJson().extractingPath("$.steps[1].outcome").asString()
                .isEqualTo("DENY");
        assertThat(result).bodyJson().extractingPath("$.steps[3].outcome").asString()
                .isEqualTo("SKIP");
        assertThat(result).bodyJson().doesNotHavePath("$.resulting_status");
        assertThat(result).bodyJson().extractingPath("$.releasable").asBoolean().isFalse();
    }

    @Test
    void anEngineerAndAReviewerAreForbidden() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + engineerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(engineer.getId(), pipeline.getId(), environment.getId()))
                .exchange()).hasStatus(403);
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(engineer.getId(), pipeline.getId(), environment.getId()))
                .exchange()).hasStatus(403);
    }

    @Test
    void anonymousCallersAreRejected() {
        assertThat(mvc.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(engineer.getId(), pipeline.getId(), environment.getId()))
                .exchange()).hasStatus(401);
    }

    @Test
    void anUnknownPipelineIsNotFound() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(engineer.getId(), UUID.randomUUID(), environment.getId()))
                .exchange()).hasStatus(404);
    }

    @Test
    void anEnvironmentOnAnotherPipelineIsNotFound() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(engineer.getId(), pipeline.getId(), UUID.randomUUID()))
                .exchange()).hasStatus(404);
    }

    @Test
    void aMissingVersionIsAValidationError() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s","pipeline_id":"%s","environment_id":"%s","version":"  "}
                        """.formatted(engineer.getId(), pipeline.getId(), environment.getId()))
                .exchange()).hasStatus(400);
    }

    @Test
    void aMalformedInstantIsAClientErrorNotAServerOne() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s","pipeline_id":"%s","environment_id":"%s","version":"2.6.0",
                         "at":"not-an-instant"}
                        """.formatted(engineer.getId(), pipeline.getId(), environment.getId()))
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

    private DeploymentPipelineEntity savePipeline(String name) {
        var entity = new DeploymentPipelineEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setName(name);
        entity.setProvider(PipelineProvider.GITHUB_ACTIONS);
        entity.setActive(true);
        entity.setAiAnalysisEnabled(false);
        return pipelineRepository.save(entity);
    }

    private DeploymentEnvironmentEntity saveEnvironment(String name) {
        var entity = new DeploymentEnvironmentEntity();
        entity.setId(UUID.randomUUID());
        entity.setPipelineId(pipeline.getId());
        entity.setName(name);
        entity.setSortOrder(1);
        entity.setRequireReview(true);
        entity.setAllowBreakGlass(false);
        return environmentRepository.save(entity);
    }

    private void givenTriggerGrant(UserEntity user) {
        var entity = new DeploymentPipelineUserPermissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setPipelineId(pipeline.getId());
        entity.setUserId(user.getId());
        entity.setCanTrigger(true);
        entity.setCreatedBy(admin.getId());
        permissionRepository.save(entity);
    }

    private void saveFreezeWindow(Instant startsAt, Instant endsAt) {
        var entity = new DeploymentFreezeWindowEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setReason("Holiday change freeze");
        entity.setBehavior(FreezeBehavior.HOLD);
        entity.setStartsAt(startsAt);
        entity.setEndsAt(endsAt);
        entity.setEnabled(true);
        freezeWindowRepository.save(entity);
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
