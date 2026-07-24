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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
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

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity submitter;
    private DatasourceEntity datasource;
    private String auditorToken;
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
