package com.bablsoft.accessflow.compliance.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.TestSystemRoleSeeder;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CreateDataClassificationTagCommand;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationAdminService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentEnvironmentCommand;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySnapshotEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QuerySnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ComplianceReportControllerIntegrationTest {

    private static final String PERIOD = "from=2026-01-01T00:00:00Z&to=2026-12-01T00:00:00Z";

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired QuerySnapshotRepository snapshotRepository;
    @Autowired DataClassificationAdminService dataClassificationAdminService;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtService jwtService;
    @Autowired DeploymentPipelineAdminService pipelineAdminService;
    @Autowired DeploymentRequestRepository deploymentRequestRepository;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity submitter;
    private DatasourceEntity datasource;
    private String auditorToken;
    private String adminToken;
    private String analystToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        cleanup();
        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID());
        organizationRepository.save(org);

        submitter = saveUser("submitter@example.com", UserRoleType.ANALYST);
        auditorToken = generateToken(saveUser("auditor@example.com", UserRoleType.AUDITOR));
        adminToken = generateToken(saveUser("admin@example.com", UserRoleType.ADMIN));
        analystToken = generateToken(saveUser("analyst@example.com", UserRoleType.ANALYST));

        datasource = datasourceRepository.save(newDatasource());
        dataClassificationAdminService.create(datasource.getId(), org.getId(),
                new CreateDataClassificationTagCommand("customers", "ssn",
                        List.of(DataClassification.PII), null, false));

        saveSnapshot(QueryType.SELECT, List.of("public.customers"), "[]",
                Instant.parse("2026-06-01T10:00:00Z"));
        saveSnapshot(QueryType.DELETE, List.of("public.customers"),
                "[{\"reviewer\":{\"email\":\"rev@example.com\",\"displayName\":\"Rev\"},"
                        + "\"decision\":\"APPROVED\",\"decidedAt\":\"2026-06-01T11:00:00Z\"}]",
                Instant.parse("2026-06-01T11:00:00Z"));
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("TRUNCATE TABLE organizations CASCADE");
        TestSystemRoleSeeder.reseedSystemRoles(jdbcTemplate);
    }

    @Test
    void auditorGetsClassifiedAccessReport() {
        var result = mvc.get().uri("/api/v1/admin/compliance/reports/classified-access?" + PERIOD)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.type").asString().isEqualTo("CLASSIFIED_ACCESS");
        assertThat(result).bodyJson().extractingPath("$.classified_access").asArray().isNotEmpty();
        assertThat(result).bodyJson()
                .extractingPath("$.classified_access[0].matched[0].classification").asString()
                .isEqualTo("PII");
    }

    @Test
    void adminGetsRegulatoryAuditTrailWithApprovers() {
        var result = mvc.get().uri("/api/v1/admin/compliance/reports/regulatory-audit-trail?" + PERIOD)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.audit_trail").asArray().hasSize(1);
        assertThat(result).bodyJson().extractingPath("$.audit_trail[0].query_type").asString()
                .isEqualTo("DELETE");
        assertThat(result).bodyJson().extractingPath("$.audit_trail[0].approvers[0].email").asString()
                .isEqualTo("rev@example.com");
    }

    @Test
    void analystIsForbidden() {
        var result = mvc.get().uri("/api/v1/admin/compliance/reports/classified-access?" + PERIOD)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(result).hasStatus(403);
    }

    @Test
    void invalidPeriodIs400() {
        var result = mvc.get().uri("/api/v1/admin/compliance/reports/classified-access"
                        + "?from=2026-12-01T00:00:00Z&to=2026-01-01T00:00:00Z")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange();
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("INVALID_REPORT_PERIOD");
    }

    @Test
    void pdfExportIsSignedAndChainedIntoAuditLog() throws Exception {
        var result = mvc.get().uri("/api/v1/admin/compliance/reports/export"
                        + "?type=CLASSIFIED_ACCESS&format=PDF&" + PERIOD)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .matches("attachment; filename=\"compliance-classified-access-\\d{8}T\\d{6}Z\\.pdf\"");
        var sha = result.getResponse().getHeader("X-AccessFlow-Content-SHA256");
        assertThat(sha).hasSize(64);
        assertThat(result.getResponse().getHeader("X-AccessFlow-Signature")).isNotBlank();
        assertThat(result.getResponse().getHeader("X-AccessFlow-Signature-Algorithm"))
                .isEqualTo("SHA256withRSA");
        assertThat(result.getResponse().getContentAsByteArray()).startsWith(new byte[]{'%', 'P', 'D', 'F'});

        // The export is chained into the audit log with the content hash (verify via admin read).
        var audit = mvc.get().uri("/api/v1/admin/audit-log?action=COMPLIANCE_REPORT_EXPORTED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(audit).hasStatus(200);
        assertThat(audit).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(1);
        assertThat(audit).bodyJson().extractingPath("$.content[0].resource_type").asString()
                .isEqualTo("compliance_report");
        assertThat(audit).bodyJson().extractingPath("$.content[0].metadata.content_sha256").asString()
                .isEqualTo(sha);
        assertThat(audit).bodyJson().extractingPath("$.content[0].metadata.format").asString()
                .isEqualTo("PDF");
    }

    @Test
    void csvExportStreamsCsv() throws Exception {
        var result = mvc.get().uri("/api/v1/admin/compliance/reports/export"
                        + "?type=CLASSIFIED_ACCESS&format=CSV&" + PERIOD)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getContentAsString())
                .startsWith("query_request_id,datasource_id,datasource_name")
                .contains("customers.ssn:PII");
    }

    @Test
    void auditorGetsDeploymentMetricsPerPipelineAndEnvironment() {
        var pipeline = seedPipelineWithEnvironment();

        var result = mvc.get().uri("/api/v1/admin/compliance/reports/deployment-metrics?" + PERIOD)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.type").asString()
                .isEqualTo("DEPLOYMENT_METRICS");
        assertThat(result).bodyJson().extractingPath("$.row_count").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.deployment_metrics[0].pipeline_id")
                .asString().isEqualTo(pipeline.toString());
        assertThat(result).bodyJson().extractingPath("$.deployment_metrics[0].environment_name")
                .asString().isEqualTo("prod");
        assertThat(result).bodyJson().extractingPath("$.deployment_metrics[0].deployments")
                .asNumber().isEqualTo(0);
    }

    @Test
    void deploymentMetricsRejectsAnEmptyWindow() {
        var result = mvc.get().uri("/api/v1/admin/compliance/reports/deployment-metrics"
                        + "?from=2026-06-01T00:00:00Z&to=2026-06-01T00:00:00Z")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange();
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("INVALID_REPORT_PERIOD");
    }

    @Test
    void deploymentMetricsForbiddenForAnalyst() {
        var result = mvc.get().uri("/api/v1/admin/compliance/reports/deployment-metrics?" + PERIOD)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(result).hasStatus(403);
    }

    @Test
    void deploymentMetricsCsvExportIsSigned() throws Exception {
        var pipeline = seedPipelineWithEnvironment();

        var result = mvc.get().uri("/api/v1/admin/compliance/reports/export"
                        + "?type=DEPLOYMENT_METRICS&format=CSV&datasourceId=" + datasource.getId()
                        + "&" + PERIOD)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .matches("attachment; filename=\"compliance-deployment-metrics-\\d{8}T\\d{6}Z\\.csv\"");
        assertThat(result.getResponse().getHeader("X-AccessFlow-Signature")).isNotBlank();
        assertThat(result.getResponse().getHeader("X-AccessFlow-Content-SHA256")).hasSize(64);
        assertThat(result.getResponse().getContentAsString())
                .startsWith("pipeline_id,pipeline_name,environment_id,environment_name,deployments,")
                .contains(pipeline + ",");

        var audit = mvc.get().uri("/api/v1/admin/audit-log?action=COMPLIANCE_REPORT_EXPORTED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(audit).bodyJson().extractingPath("$.content[0].metadata.report_type").asString()
                .isEqualTo("DEPLOYMENT_METRICS");
        assertThat(audit).bodyJson().doesNotHavePath("$.content[0].metadata.datasource_id");
    }

    @Test
    void deploymentMetricsKeepCaseOnlyDistinctEnvironmentsApart() {
        var pipeline = pipelineAdminService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "payments-" + UUID.randomUUID(), PipelineProvider.GITHUB_ACTIONS, null, null, null,
                false, null));
        var upper = pipelineAdminService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("Prod", null, true, null, null, false,
                        List.of(), null, false));
        var lower = pipelineAdminService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("prod", null, true, null, null, false,
                        List.of(), null, false));
        executedDeployment(pipeline.id(), upper.id(), "1.0.0");
        executedDeployment(pipeline.id(), lower.id(), "1.0.1");

        var result = mvc.get().uri("/api/v1/admin/compliance/reports/deployment-metrics?" + PERIOD)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.row_count").asNumber().isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.deployment_metrics[0].deployments")
                .asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.deployment_metrics[1].deployments")
                .asNumber().isEqualTo(1);
    }

    private void executedDeployment(UUID pipelineId, UUID environmentId, String version) {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setPipelineId(pipelineId);
        entity.setEnvironmentId(environmentId);
        entity.setSubmittedBy(submitter.getId());
        entity.setVersion(version);
        entity.setStatus(QueryStatus.EXECUTED);
        entity.setOutcome(DeploymentOutcome.SUCCEEDED);
        entity.setOutcomeReportedAt(Instant.parse("2026-06-01T10:05:00Z"));
        entity.setExecutedAt(Instant.parse("2026-06-01T10:00:00Z"));
        entity.setCreatedAt(Instant.parse("2026-06-01T09:00:00Z"));
        deploymentRequestRepository.saveAndFlush(entity);
    }

    private UUID seedPipelineWithEnvironment() {
        var pipeline = pipelineAdminService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "payments-" + UUID.randomUUID(), PipelineProvider.GITHUB_ACTIONS, null, null, null,
                false, null));
        pipelineAdminService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("prod", null, true, null, null, false,
                        List.of(), null, false));
        return pipeline.id();
    }

    @Test
    void signingCertificateReturnsPublicKey() {
        var result = mvc.get().uri("/api/v1/admin/compliance/signing-certificate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.algorithm").asString().isEqualTo("SHA256withRSA");
        assertThat(result).bodyJson().extractingPath("$.public_key_pem").asString()
                .startsWith("-----BEGIN PUBLIC KEY-----");
    }

    @Test
    void signingCertificateForbiddenForAnalyst() {
        var result = mvc.get().uri("/api/v1/admin/compliance/signing-certificate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(result).hasStatus(403);
    }

    private void saveSnapshot(QueryType type, List<String> tables, String reviewDecisionsJson,
                              Instant executedAt) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(datasource);
        query.setSubmittedBy(submitter);
        query.setSqlText("SQL");
        query.setQueryType(type);
        query.setStatus(QueryStatus.EXECUTED);
        queryRequestRepository.save(query);

        var snapshot = new QuerySnapshotEntity();
        snapshot.setId(UUID.randomUUID());
        snapshot.setQueryRequestId(query.getId());
        snapshot.setOrganizationId(org.getId());
        snapshot.setDatasourceId(datasource.getId());
        snapshot.setSubmittedBy(submitter.getId());
        snapshot.setSqlText("SELECT * FROM customers");
        snapshot.setQueryType(type);
        snapshot.setDbType(DbType.POSTGRESQL);
        snapshot.setReferencedTables(tables.toArray(String[]::new));
        snapshot.setReviewDecisionsJson(reviewDecisionsJson);
        snapshot.setExecutedAt(executedAt);
        snapshotRepository.save(snapshot);
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash("hashed");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private DatasourceEntity newDatasource() {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName("Prod");
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(5432);
        ds.setDatabaseName("db");
        ds.setUsername("u");
        ds.setPasswordEncrypted(encryptionService.encrypt("p"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(5);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return ds;
    }

    private String generateToken(UserEntity entity) {
        var view = new UserView(entity.getId(), entity.getEmail(), entity.getDisplayName(),
                entity.getRole(), entity.getOrganization().getId(), entity.isActive(),
                entity.getAuthProvider(), entity.getPasswordHash(), entity.getLastLoginAt(),
                entity.getPreferredLanguage(), entity.isTotpEnabled(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
