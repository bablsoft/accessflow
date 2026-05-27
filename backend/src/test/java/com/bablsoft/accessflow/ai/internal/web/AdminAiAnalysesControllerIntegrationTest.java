package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminAiAnalysesControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired AiAnalysisRepository aiAnalysisRepository;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private OrganizationEntity orgPrimary;
    private OrganizationEntity orgOther;
    private UserEntity adminPrimary;
    private UserEntity analystPrimary;
    private DatasourceEntity dsPrimaryA;
    private DatasourceEntity dsPrimaryB;
    private DatasourceEntity dsOther;
    private String adminToken;

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

        orgPrimary = saveOrg("Primary");
        orgOther = saveOrg("Other");
        adminPrimary = saveUser(orgPrimary, "admin@primary.test", UserRoleType.ADMIN);
        analystPrimary = saveUser(orgPrimary, "alice@primary.test", "Alice", UserRoleType.ANALYST);
        var analystOther = saveUser(orgOther, "bob@other.test", "Bob", UserRoleType.ANALYST);

        dsPrimaryA = saveDatasource(orgPrimary, "primary-A");
        dsPrimaryB = saveDatasource(orgPrimary, "primary-B");
        dsOther = saveDatasource(orgOther, "other-only");

        // Primary org: 3 successful analyses on dsPrimaryA + 1 failed on dsPrimaryB + 1 on dsPrimaryA from a different day.
        var t1 = Instant.parse("2026-05-10T10:00:00Z");
        var t2 = Instant.parse("2026-05-10T11:00:00Z");
        var t3 = Instant.parse("2026-05-11T09:00:00Z");
        var t4 = Instant.parse("2026-05-12T09:00:00Z");
        seedAnalysis(orgPrimary, dsPrimaryA, analystPrimary, t1, 40, RiskLevel.MEDIUM, false,
                "[{\"severity\":\"MEDIUM\",\"category\":\"performance\",\"message\":\"slow\",\"suggestion\":\"x\"}]");
        seedAnalysis(orgPrimary, dsPrimaryA, analystPrimary, t2, 60, RiskLevel.HIGH, false,
                "[{\"severity\":\"HIGH\",\"category\":\"performance\",\"message\":\"slower\",\"suggestion\":\"y\"},"
                        + "{\"severity\":\"MEDIUM\",\"category\":\"security\",\"message\":\"sec\",\"suggestion\":\"z\"}]");
        seedAnalysis(orgPrimary, dsPrimaryB, analystPrimary, t3, 80, RiskLevel.HIGH, false,
                "[{\"severity\":\"HIGH\",\"category\":\"security\",\"message\":\"!\",\"suggestion\":\"!\"}]");
        seedAnalysis(orgPrimary, dsPrimaryA, analystPrimary, t4, 100, RiskLevel.CRITICAL, true, "[]");

        // Other org: 1 successful analysis on its own datasource. Must NEVER leak into primary's stats.
        seedAnalysis(orgOther, dsOther, analystOther, t2, 25, RiskLevel.LOW, false,
                "[{\"severity\":\"LOW\",\"category\":\"performance\",\"message\":\"other\",\"suggestion\":\"o\"}]");

        adminToken = generateToken(adminPrimary);
    }

    @AfterEach
    void tearDown() {
        // Leave the schema clean so sibling integration tests that don't know
        // about query_requests / ai_analyses can wipe datasources without
        // tripping the FK from query_requests.
        cleanup();
    }

    private void cleanup() {
        aiAnalysisRepository.deleteAll();
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void statsScopesToCallerOrganization() {
        var result = mvc.get().uri("/api/v1/admin/ai-analyses/stats"
                        + "?from=2026-05-01T00:00:00Z&to=2026-06-01T00:00:00Z")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        // Top submitters lists only the primary-org user (Alice 4 rows), never Bob from orgOther.
        assertThat(result).bodyJson().extractingPath("$.top_submitters").asArray().hasSize(1);
        assertThat(result).bodyJson().extractingPath("$.top_submitters[0].email")
                .asString().isEqualTo("alice@primary.test");
        assertThat(result).bodyJson().extractingPath("$.top_submitters[0].count")
                .asNumber().isEqualTo(4);
        // Top issue categories — only primary-org issues. 'performance' appears in 2 rows, 'security' in 2 rows.
        assertThat(result).bodyJson().extractingPath("$.top_issue_categories").asArray().isNotEmpty();
    }

    @Test
    void riskScoreBucketsExcludeFailedFromAverage() {
        var result = mvc.get().uri("/api/v1/admin/ai-analyses/stats"
                        + "?from=2026-05-01T00:00:00Z&to=2026-06-01T00:00:00Z")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        // Day with two successful rows (40, 60) → success_avg = 50, total = 2, success = 2.
        // Day with only a failed row → total = 1, success = 0.
        // The exact bucket date depends on the Postgres session timezone — assert by content,
        // not by hardcoded date string.
        assertThat(result).bodyJson().extractingPath("$.risk_score_over_time[*].total_count")
                .asArray().containsExactlyInAnyOrder(2, 1, 1);
        assertThat(result).bodyJson().extractingPath("$.risk_score_over_time[*].success_count")
                .asArray().containsExactlyInAnyOrder(2, 1, 0);
        // Of the successful-only buckets, the per-day averages are 50 (40 + 60) / 2 and 80.
        // The failed-only bucket has null success_avg, which Jackson's default-inclusion=non_null
        // strips, so the array is short by one element. BigDecimal serializes as full-precision
        // numeric (e.g. 50.0000000000000000) so we compareTo on BigDecimal equivalents.
        assertThat(result).bodyJson().extractingPath("$.risk_score_over_time[*].success_avg_risk_score")
                .asArray()
                .extracting(o -> new java.math.BigDecimal(o.toString()).intValue())
                .containsExactlyInAnyOrder(50, 80);
    }

    @Test
    void datasourceFilterNarrowsToOneDatasource() {
        var result = mvc.get().uri("/api/v1/admin/ai-analyses/stats"
                        + "?from=2026-05-01T00:00:00Z&to=2026-06-01T00:00:00Z"
                        + "&datasourceId=" + dsPrimaryB.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        // dsPrimaryB had only 1 row (the t3 one) → top_submitters count is 1.
        assertThat(result).bodyJson().extractingPath("$.top_submitters[0].count").asNumber().isEqualTo(1);
    }

    @Test
    void invalidRangeReturns400() {
        var result = mvc.get().uri("/api/v1/admin/ai-analyses/stats"
                        + "?from=2026-06-01T00:00:00Z&to=2026-05-01T00:00:00Z")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("BAD_AI_ANALYSIS_STATS_QUERY");
    }

    @Test
    void analystGets403() {
        var analystToken = generateToken(analystPrimary);
        var result = mvc.get().uri("/api/v1/admin/ai-analyses/stats")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private OrganizationEntity saveOrg(String name) {
        var o = new OrganizationEntity();
        o.setId(UUID.randomUUID());
        o.setName(name);
        o.setSlug(name.toLowerCase() + "-" + UUID.randomUUID());
        return organizationRepository.save(o);
    }

    private UserEntity saveUser(OrganizationEntity org, String email, UserRoleType role) {
        return saveUser(org, email, role.name(), role);
    }

    private UserEntity saveUser(OrganizationEntity org, String email, String displayName, UserRoleType role) {
        var u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(displayName);
        u.setPasswordHash("hashed");
        u.setRole(role);
        u.setAuthProvider(AuthProviderType.LOCAL);
        u.setActive(true);
        u.setOrganization(org);
        return userRepository.save(u);
    }

    private DatasourceEntity saveDatasource(OrganizationEntity org, String name) {
        var d = new DatasourceEntity();
        d.setId(UUID.randomUUID());
        d.setOrganization(org);
        d.setName(name);
        d.setDbType(DbType.POSTGRESQL);
        d.setHost("h");
        d.setPort(5432);
        d.setDatabaseName("db");
        d.setUsername("u");
        d.setPasswordEncrypted("ENC");
        d.setAiAnalysisEnabled(false);
        d.setActive(true);
        d.setCreatedAt(Instant.now());
        return datasourceRepository.save(d);
    }

    private QueryRequestEntity seedAnalysis(OrganizationEntity org, DatasourceEntity ds, UserEntity submitter,
                                            Instant when, int riskScore, RiskLevel level, boolean failed,
                                            String issuesJson) {
        var qr = new QueryRequestEntity();
        qr.setId(UUID.randomUUID());
        qr.setDatasource(ds);
        qr.setSubmittedBy(submitter);
        qr.setSqlText("SELECT 1");
        qr.setQueryType(QueryType.SELECT);
        qr.setStatus(QueryStatus.PENDING_REVIEW);
        qr.setCreatedAt(when);
        qr.setUpdatedAt(when);
        queryRequestRepository.save(qr);

        var a = new AiAnalysisEntity();
        a.setId(UUID.randomUUID());
        a.setQueryRequest(qr);
        a.setAiProvider(AiProviderType.OPENAI);
        a.setAiModel("gpt-4o-mock");
        a.setRiskScore(riskScore);
        a.setRiskLevel(level);
        a.setSummary("seeded");
        a.setIssues(issuesJson);
        a.setFailed(failed);
        a.setErrorMessage(failed ? "mock failure" : null);
        a.setCreatedAt(when.truncatedTo(ChronoUnit.SECONDS));
        aiAnalysisRepository.save(a);
        return qr;
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
