package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The access-simulation endpoint end to end (AF-859) — the permission gate, org scoping, the audit
 * row, and above all the read-only guarantee: a simulation must leave no {@code query_requests} row
 * behind.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminAccessSimulationControllerIntegrationTest {

    private static final String BASE = "/api/v1/admin/access-simulations";

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private DatasourceEntity datasource;
    private UserEntity admin;
    private UserEntity analyst;
    private UserEntity reviewer;
    private UserEntity auditor;
    private String adminToken;
    private String analystToken;
    private String reviewerToken;
    private String auditorToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        org = saveOrg("Explainer " + suffix, "explainer-" + suffix);
        admin = saveUser("admin-sim-" + suffix + "@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst-sim-" + suffix + "@example.com", UserRoleType.ANALYST);
        reviewer = saveUser("reviewer-sim-" + suffix + "@example.com", UserRoleType.REVIEWER);
        auditor = saveUser("auditor-sim-" + suffix + "@example.com", UserRoleType.AUDITOR);
        datasource = saveDatasource("Explainer-DS-" + suffix);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
        reviewerToken = generateToken(reviewer);
        auditorToken = generateToken(auditor);
        givenGrant(analyst, true, false, false, new String[] {"orders"});
    }

    /** Scoped to this class's own rows — the Testcontainers database is shared. */
    @AfterEach
    void cleanup() {
        if (datasource == null) {
            return;
        }
        jdbcTemplate.update("delete from audit_log where organization_id = ?", org.getId());
        permissionRepository.deleteAll(permissionRepository.findAll().stream()
                .filter(p -> datasource.getId().equals(p.getDatasource().getId()))
                .toList());
        datasourceRepository.deleteById(datasource.getId());
        userRepository.deleteAllById(
                java.util.List.of(admin.getId(), analyst.getId(), reviewer.getId(),
                        auditor.getId()));
        organizationRepository.deleteById(org.getId());
        datasource = null;
    }

    private String body(UUID userId, UUID datasourceId, String sql) {
        return """
                {"user_id":"%s","datasource_id":"%s","sql":"%s","ai_outcome":"SKIPPED"}
                """.formatted(userId, datasourceId, sql);
    }

    @Test
    void anAdminGetsTheFullOrderedTrace() {
        var result = mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), datasource.getId(), "SELECT id FROM orders"))
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.steps.length()").asNumber().isEqualTo(11);
        assertThat(result).bodyJson().extractingPath("$.steps[0].step").asString()
                .isEqualTo("DATASOURCE_GATES");
        assertThat(result).bodyJson().extractingPath("$.steps[10].step").asString()
                .isEqualTo("BREAK_GLASS");
        assertThat(result).bodyJson().extractingPath("$.resulting_status").asString()
                .isEqualTo("PENDING_REVIEW");
        // Every step carries a resolved sentence, not a raw message key.
        assertThat(result).bodyJson().extractingPath("$.steps[0].reason").asString()
                .doesNotContain("workflow.access_simulation");
    }

    @Test
    void aSimulationCreatesNoQueryRequestAndNoOtherTrace() {
        long queriesBefore = queryRequestRepository.count();

        mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), datasource.getId(), "SELECT id FROM orders"))
                .exchange();

        assertThat(queryRequestRepository.count()).isEqualTo(queriesBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from routing_decision", Long.class)).isZero();
    }

    @Test
    void everySimulationWritesExactlyOneAuditRow() {
        mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), datasource.getId(), "SELECT id FROM orders"))
                .exchange();

        var rows = jdbcTemplate.queryForList(
                "select action, resource_id, metadata::text from audit_log "
                        + "where organization_id = ? and action = ?",
                org.getId(), AuditAction.ACCESS_SIMULATION_RUN.name());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("resource_id")).hasToString(datasource.getId().toString());
        var metadata = String.valueOf(rows.get(0).get("metadata"));
        assertThat(metadata).contains(analyst.getId().toString()).contains("PENDING_REVIEW");
        // The SQL is deliberately absent: DATASOURCE_PERMISSION_MANAGE does not grant read access
        // to query text, and an audit row must not become a way around that.
        assertThat(metadata).doesNotContain("SELECT id FROM orders");
    }

    @Test
    void theTraceExplainsWhyAnAllowListRejectionWouldHappen() {
        var result = mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), datasource.getId(), "SELECT id FROM payroll"))
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.steps[3].step").asString()
                .isEqualTo("EFFECTIVE_PERMISSION");
        assertThat(result).bodyJson().extractingPath("$.steps[3].outcome").asString()
                .isEqualTo("DENY");
        assertThat(result).bodyJson().extractingPath("$.steps[3].details.rejected_tables[0]")
                .asString().isEqualTo("payroll");
        // No status is assigned to a request the submission gate would refuse, and Jackson omits the
        // field entirely rather than sending null — so a client sees it as absent.
        assertThat(result).bodyJson().doesNotHavePath("$.resulting_status");
    }

    @Test
    void aQueryAdminHolderWithNoPermissionRowStillPasses() {
        var result = mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(admin.getId(), datasource.getId(), "DELETE FROM payroll"))
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson()
                .extractingPath("$.steps[3].details.query_admin_short_circuit").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.steps[3].outcome").asString()
                .isEqualTo("ALLOW");
    }

    @Test
    void theResponseNamesTheApproximationsItMade() {
        var result = mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), datasource.getId(), "SELECT id FROM orders"))
                .exchange();

        assertThat(result).bodyJson().extractingPath("$.caveats").asArray()
                .contains("CLIENT_CONTEXT_ABSENT", "COST_ESTIMATE_ABSENT");
    }

    @Test
    void ananalystAndAReviewerAreForbidden() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), datasource.getId(), "SELECT 1"))
                .exchange()).hasStatus(403);
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), datasource.getId(), "SELECT 1"))
                .exchange()).hasStatus(403);
    }

    @Test
    void anAuditorIsForbiddenFromTheSimulator() {
        // ACCESS_USAGE_REPORT_VIEW opens the reverse index, not the trace: a trace discloses routing
        // and row-security configuration an auditor has no other route to.
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), datasource.getId(), "SELECT 1"))
                .exchange()).hasStatus(403);
    }

    @Test
    void anonymousCallersAreRejected() {
        assertThat(mvc.post().uri(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), datasource.getId(), "SELECT 1"))
                .exchange()).hasStatus(401);
    }

    @Test
    void anUnknownDatasourceIsNotFound() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), UUID.randomUUID(), "SELECT 1"))
                .exchange()).hasStatus(404);
    }

    @Test
    void anUnknownUserIsNotFound() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(UUID.randomUUID(), datasource.getId(), "SELECT 1"))
                .exchange()).hasStatus(404);
    }

    @Test
    void aMissingSqlIsAValidationError() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s","datasource_id":"%s","sql":"  "}
                        """.formatted(analyst.getId(), datasource.getId()))
                .exchange()).hasStatus(400);
    }

    @Test
    void aDetailWithNoValueIsOmittedRatherThanSentAsNull() {
        // spring.jackson.default-property-inclusion=non_null strips null map content as well as null
        // POJO properties, so a stage that records "no review plan" simply has no review_plan_id
        // key. Pinned here because the API spec documents the absence as meaningful.
        var result = mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(analyst.getId(), datasource.getId(), "SELECT id FROM orders"))
                .exchange();

        assertThat(result).bodyJson().extractingPath("$.steps[6].step").asString()
                .isEqualTo("REVIEW_PLAN");
        assertThat(result).bodyJson().doesNotHavePath("$.steps[6].details.review_plan_id");
        assertThat(result).bodyJson().doesNotHavePath("$.steps[6].details.min_approvals_required");
        assertThat(result).bodyJson().extractingPath("$.steps[6].details.requires_human_approval")
                .asBoolean().isFalse();
    }

    @Test
    void aMalformedAiOutcomeIsAClientErrorNotAServerOne() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s","datasource_id":"%s","sql":"SELECT 1","ai_outcome":"NOPE"}
                        """.formatted(analyst.getId(), datasource.getId()))
                .exchange()).hasStatus(400);
    }

    @Test
    void anOutOfRangeRiskScoreIsRejected() {
        assertThat(mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"user_id":"%s","datasource_id":"%s","sql":"SELECT 1","risk_score":500}
                        """.formatted(analyst.getId(), datasource.getId()))
                .exchange()).hasStatus(400);
    }

    private void givenGrant(UserEntity user, boolean read, boolean write, boolean ddl,
                            String[] allowedTables) {
        var permission = new DatasourceUserPermissionEntity();
        permission.setId(UUID.randomUUID());
        permission.setDatasource(datasource);
        permission.setUser(user);
        permission.setCanRead(read);
        permission.setCanWrite(write);
        permission.setCanDdl(ddl);
        permission.setAllowedTables(allowedTables);
        permission.setCreatedBy(admin);
        permissionRepository.save(permission);
    }

    private OrganizationEntity saveOrg(String name, String slug) {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setSlug(slug);
        return organizationRepository.save(entity);
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private DatasourceEntity saveDatasource(String name) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName(name);
        ds.setDbType(DbType.POSTGRESQL);
        // Unroutable on purpose: a green run is itself the proof that nothing connects.
        ds.setHost("nope.invalid");
        ds.setPort(65000);
        ds.setDatabaseName("appdb");
        ds.setUsername("svc");
        ds.setPasswordEncrypted(encryptionService.encrypt("seed-password"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(10);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return datasourceRepository.save(ds);
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
