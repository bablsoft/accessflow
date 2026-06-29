package com.bablsoft.accessflow.dashboard.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
import com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorAnomalyEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.BehaviorAnomalyRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
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
class DashboardControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired AiAnalysisRepository aiAnalysisRepository;
    @Autowired BehaviorAnomalyRepository anomalyRepository;
    @Autowired ApiRequestRepository apiRequestRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity me;
    private UserEntity other;
    private DatasourceEntity datasource;
    private String myToken;

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var pk = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pk.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key",
                () -> "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
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
        me = saveUser("me@example.com");
        other = saveUser("other@example.com");
        myToken = token(me);
        datasource = saveDatasource();
    }

    @AfterEach
    void cleanup() {
        // api_requests carries a bare organization_id (no FK), so it is not reached by the
        // organizations cascade — truncate it explicitly (CASCADE clears the ai_analyses back-refs).
        jdbcTemplate.execute("TRUNCATE TABLE api_requests CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE organizations CASCADE");
    }

    @Test
    void summaryIsSelfScoped() {
        seedQueryWithAnalysis(me, QueryStatus.PENDING_REVIEW);
        seedQueryWithAnalysis(other, QueryStatus.PENDING_REVIEW); // not mine

        var result = mvc.get().uri("/api/v1/dashboard/summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.open_queries_count").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.recent_queries").asArray().hasSize(1);
    }

    @Test
    void trendsReturnsSeries() {
        seedQueryWithAnalysis(me, QueryStatus.EXECUTED);
        var result = mvc.get().uri("/api/v1/dashboard/my-query-trends")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.status_by_day").asArray().isNotEmpty();
    }

    @Test
    void summaryIncludesSelfScopedApiRequests() {
        seedApiRequest(me, QueryStatus.PENDING_REVIEW);
        seedApiRequest(other, QueryStatus.PENDING_REVIEW); // not mine

        var result = mvc.get().uri("/api/v1/dashboard/summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.open_api_requests_count").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.recent_api_requests").asArray().hasSize(1);
    }

    @Test
    void apiRequestTrendsReturnsSeries() {
        seedApiRequest(me, QueryStatus.EXECUTED);
        var result = mvc.get().uri("/api/v1/dashboard/my-api-request-trends")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.status_by_day").asArray().isNotEmpty();
    }

    @Test
    void suggestionsListAndDismiss() {
        var analysisId = seedQueryWithAnalysis(me, QueryStatus.EXECUTED);

        var list = mvc.get().uri("/api/v1/dashboard/suggestions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();
        assertThat(list).hasStatus(200);
        assertThat(list).bodyJson().extractingPath("$.suggestions").asArray().hasSize(1);

        var dismiss = mvc.post().uri("/api/v1/dashboard/suggestions/" + analysisId + ":0/dismiss")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();
        assertThat(dismiss).hasStatus(204);

        var after = mvc.get().uri("/api/v1/dashboard/suggestions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();
        assertThat(after).bodyJson().extractingPath("$.suggestions").asArray().isEmpty();
    }

    @Test
    void dismissUnknownSuggestionIs404() {
        var result = mvc.post().uri("/api/v1/dashboard/suggestions/" + UUID.randomUUID() + ":0/dismiss")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();
        assertThat(result).hasStatus(404);
    }

    @Test
    void digestSubscriptionToggles() {
        var initial = mvc.get().uri("/api/v1/dashboard/digest-subscription")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();
        assertThat(initial).bodyJson().extractingPath("$.enabled").isEqualTo(false);

        var enable = mvc.put().uri("/api/v1/dashboard/digest-subscription")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}").exchange();
        assertThat(enable).hasStatus(200);
        assertThat(enable).bodyJson().extractingPath("$.enabled").isEqualTo(true);

        var after = mvc.get().uri("/api/v1/dashboard/digest-subscription")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();
        assertThat(after).bodyJson().extractingPath("$.enabled").isEqualTo(true);
    }

    @Test
    void exportPdfIsSigned() {
        var result = mvc.get().uri("/api/v1/dashboard/summary/export?format=PDF")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();
        assertThat(result).hasStatus(200);
        assertThat(result.getResponse().getHeader("X-AccessFlow-Signature")).isNotBlank();
        assertThat(result.getResponse().getHeader("X-AccessFlow-Content-SHA256")).isNotBlank();
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
    }

    @Test
    void anomaliesMineReturnsOnlyOwn() {
        seedAnomaly(me);
        seedAnomaly(other);

        var result = mvc.get().uri("/api/v1/anomalies/mine")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.content[0].user_id").asString()
                .isEqualTo(me.getId().toString());
    }

    @Test
    void cannotAcknowledgeAnotherUsersAnomaly() {
        var foreignId = seedAnomaly(other);
        var result = mvc.post().uri("/api/v1/anomalies/mine/" + foreignId + "/acknowledge")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();
        assertThat(result).hasStatus(404);
    }

    @Test
    void acknowledgeOwnAnomaly() {
        var id = seedAnomaly(me);
        var result = mvc.post().uri("/api/v1/anomalies/mine/" + id + "/acknowledge")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + myToken).exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.status").asString().isEqualTo("ACKNOWLEDGED");
    }

    // ----- helpers -----

    private UUID seedQueryWithAnalysis(UserEntity submitter, QueryStatus status) {
        var q = new QueryRequestEntity();
        q.setId(UUID.randomUUID());
        q.setDatasource(datasource);
        q.setSubmittedBy(submitter);
        q.setSqlText("SELECT 1");
        q.setQueryType(QueryType.SELECT);
        q.setStatus(status);
        queryRequestRepository.save(q);

        var a = new AiAnalysisEntity();
        a.setId(UUID.randomUUID());
        a.setQueryRequest(q);
        a.setAiProvider(AiProviderType.ANTHROPIC);
        a.setAiModel("claude-sonnet-4");
        a.setRiskScore(40);
        a.setRiskLevel(RiskLevel.MEDIUM);
        a.setSummary("summary");
        a.setOptimizations(
                "[{\"type\":\"INDEX\",\"title\":\"Add idx\",\"rationale\":\"speed\",\"sql\":\"CREATE INDEX\"}]");
        a.setFailed(false);
        aiAnalysisRepository.save(a);

        // Re-read before the ai_analysis_id back-link update so the @Version (updated_at) carries the
        // DB-stored, microsecond-truncated value — the in-memory nanosecond Instant trips optimistic
        // locking on platforms (Linux CI) whose Instant.now() has sub-microsecond precision.
        var managed = queryRequestRepository.findById(q.getId()).orElseThrow();
        managed.setAiAnalysisId(a.getId());
        queryRequestRepository.save(managed);
        return a.getId();
    }

    private UUID seedApiRequest(UserEntity submitter, QueryStatus status) {
        var e = new ApiRequestEntity();
        e.setId(UUID.randomUUID());
        e.setConnectorId(UUID.randomUUID());
        e.setOrganizationId(org.getId());
        e.setSubmittedBy(submitter.getId());
        e.setVerb("GET");
        e.setRequestPath("/v1/things");
        e.setStatus(status);
        return apiRequestRepository.save(e).getId();
    }

    private UUID seedAnomaly(UserEntity user) {
        var e = new BehaviorAnomalyEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(org.getId());
        e.setUserId(user.getId());
        e.setDatasourceId(datasource.getId());
        e.setFeature("query_count");
        e.setScore(4.2);
        e.setDetail("{}");
        e.setStatus(BehaviorAnomalyStatus.OPEN);
        e.setDetectedAt(Instant.parse("2026-03-01T10:00:00Z"));
        e.setWindowStart(Instant.parse("2026-03-01T09:00:00Z"));
        e.setWindowEnd(Instant.parse("2026-03-01T10:00:00Z"));
        return anomalyRepository.saveAndFlush(e).getId();
    }

    private UserEntity saveUser(String email) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash("hash");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private DatasourceEntity saveDatasource() {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
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

    private String token(UserEntity entity) {
        var view = new UserView(entity.getId(), entity.getEmail(), entity.getDisplayName(),
                entity.getRole(), entity.getOrganization().getId(), entity.isActive(),
                entity.getAuthProvider(), entity.getPasswordHash(), entity.getLastLoginAt(),
                entity.getPreferredLanguage(), entity.isTotpEnabled(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
