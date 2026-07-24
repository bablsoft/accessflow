package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.TestSystemRoleSeeder;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
import com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorAnomalyEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.BehaviorAnomalyRepository;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
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

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminBehaviorAnomalyControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired BehaviorAnomalyRepository anomalyRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity admin;
    private UserEntity auditor;
    private UserEntity analyst;
    private DatasourceEntity datasource;
    private String adminToken;
    private String auditorToken;
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

        admin = saveUser("admin@example.com", UserRoleType.ADMIN);
        auditor = saveUser("auditor@example.com", UserRoleType.AUDITOR);
        analyst = saveUser("analyst@example.com", UserRoleType.ANALYST);
        adminToken = generateToken(admin);
        auditorToken = generateToken(auditor);
        analystToken = generateToken(analyst);

        datasource = saveDatasource();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("TRUNCATE TABLE organizations CASCADE");
        TestSystemRoleSeeder.reseedSystemRoles(jdbcTemplate);
    }

    // ----- list -----

    @Test
    void adminCanListAnomalies() {
        seedAnomaly(BehaviorAnomalyStatus.OPEN, "query_count");

        var result = mvc.get().uri("/api/v1/admin/anomalies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.content[0].feature").asString()
                .isEqualTo("query_count");
    }

    @Test
    void auditorCanListAnomalies() {
        seedAnomaly(BehaviorAnomalyStatus.OPEN, "active_hours");

        var result = mvc.get().uri("/api/v1/admin/anomalies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(1);
    }

    @Test
    void analystListIsForbidden() {
        var result = mvc.get().uri("/api/v1/admin/anomalies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(result).hasStatus(403);
    }

    @Test
    void listFiltersByStatus() {
        seedAnomaly(BehaviorAnomalyStatus.OPEN, "query_count");
        seedAnomaly(BehaviorAnomalyStatus.DISMISSED, "active_hours");

        var result = mvc.get().uri("/api/v1/admin/anomalies?status=OPEN")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.content[0].feature").asString()
                .isEqualTo("query_count");
    }

    // ----- get -----

    @Test
    void getReturnsAnomaly() {
        var id = seedAnomaly(BehaviorAnomalyStatus.OPEN, "query_count");

        var result = mvc.get().uri("/api/v1/admin/anomalies/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.id").asString().isEqualTo(id.toString());
        assertThat(result).bodyJson().extractingPath("$.feature").asString().isEqualTo("query_count");
    }

    @Test
    void getUnknownIdReturns404() {
        var result = mvc.get().uri("/api/v1/admin/anomalies/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(result).hasStatus(404);
    }

    // ----- acknowledge -----

    @Test
    void adminCanAcknowledgeOpenAnomaly() {
        var id = seedAnomaly(BehaviorAnomalyStatus.OPEN, "query_count");

        var result = mvc.post().uri("/api/v1/admin/anomalies/" + id + "/acknowledge")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.status").asString().isEqualTo("ACKNOWLEDGED");
        assertThat(anomalyRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(BehaviorAnomalyStatus.ACKNOWLEDGED);
    }

    @Test
    void acknowledgeNonOpenAnomalyIs409() {
        var id = seedAnomaly(BehaviorAnomalyStatus.ACKNOWLEDGED, "query_count");

        var result = mvc.post().uri("/api/v1/admin/anomalies/" + id + "/acknowledge")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(409);
    }

    @Test
    void auditorCannotAcknowledge() {
        var id = seedAnomaly(BehaviorAnomalyStatus.OPEN, "query_count");

        var result = mvc.post().uri("/api/v1/admin/anomalies/" + id + "/acknowledge")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    // ----- dismiss -----

    @Test
    void adminCanDismissOpenAnomaly() {
        var id = seedAnomaly(BehaviorAnomalyStatus.OPEN, "query_count");

        var result = mvc.post().uri("/api/v1/admin/anomalies/" + id + "/dismiss")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.status").asString().isEqualTo("DISMISSED");
    }

    @Test
    void adminCanDismissAcknowledgedAnomaly() {
        var id = seedAnomaly(BehaviorAnomalyStatus.ACKNOWLEDGED, "query_count");

        var result = mvc.post().uri("/api/v1/admin/anomalies/" + id + "/dismiss")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.status").asString().isEqualTo("DISMISSED");
    }

    @Test
    void dismissAlreadyDismissedIs409() {
        var id = seedAnomaly(BehaviorAnomalyStatus.DISMISSED, "query_count");

        var result = mvc.post().uri("/api/v1/admin/anomalies/" + id + "/dismiss")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(409);
    }

    @Test
    void getDoesNotSeeAnotherOrgsAnomaly() {
        var foreignOrg = new OrganizationEntity();
        foreignOrg.setId(UUID.randomUUID());
        foreignOrg.setName("Other");
        foreignOrg.setSlug("other-" + UUID.randomUUID());
        organizationRepository.save(foreignOrg);
        var foreignUser = new UserEntity();
        foreignUser.setId(UUID.randomUUID());
        foreignUser.setEmail("foreigner-" + UUID.randomUUID() + "@example.com");
        foreignUser.setDisplayName("Foreigner");
        foreignUser.setPasswordHash("hash");
        foreignUser.setRole(UserRoleType.ANALYST);
        foreignUser.setAuthProvider(AuthProviderType.LOCAL);
        foreignUser.setActive(true);
        foreignUser.setOrganization(foreignOrg);
        userRepository.save(foreignUser);
        var foreignDs = saveDatasourceFor(foreignOrg);

        var entity = anomalyEntity(BehaviorAnomalyStatus.OPEN, "query_count");
        entity.setOrganizationId(foreignOrg.getId());
        entity.setUserId(foreignUser.getId());
        entity.setDatasourceId(foreignDs.getId());
        anomalyRepository.saveAndFlush(entity);

        var result = mvc.get().uri("/api/v1/admin/anomalies/" + entity.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    // ----- helpers -----

    private UUID seedAnomaly(BehaviorAnomalyStatus status, String feature) {
        var entity = anomalyEntity(status, feature);
        return anomalyRepository.saveAndFlush(entity).getId();
    }

    private BehaviorAnomalyEntity anomalyEntity(BehaviorAnomalyStatus status, String feature) {
        var entity = new BehaviorAnomalyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setUserId(analyst.getId());
        entity.setDatasourceId(datasource.getId());
        entity.setFeature(feature);
        entity.setScore(4.2);
        entity.setObservedValue(100.0);
        entity.setBaselineMean(10.0);
        entity.setBaselineStddev(2.0);
        entity.setDetail("{\"method\":\"zscore\"}");
        entity.setStatus(status);
        entity.setDetectedAt(Instant.parse("2026-03-01T10:00:00Z"));
        entity.setWindowStart(Instant.parse("2026-03-01T09:00:00Z"));
        entity.setWindowEnd(Instant.parse("2026-03-01T10:00:00Z"));
        return entity;
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private DatasourceEntity saveDatasource() {
        return saveDatasourceFor(org);
    }

    private DatasourceEntity saveDatasourceFor(OrganizationEntity organization) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(organization);
        ds.setName("DS-" + UUID.randomUUID());
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(65000);
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
        return datasourceRepository.save(ds);
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
