package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.MyQueryInsightsLookupService;
import com.bablsoft.accessflow.core.api.MyQueryStatusCount;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
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
import com.bablsoft.accessflow.dashboard.api.DashboardSuggestionStatus;
import com.bablsoft.accessflow.dashboard.internal.persistence.entity.DashboardDigestSubscriptionEntity;
import com.bablsoft.accessflow.dashboard.internal.persistence.entity.DashboardSuggestionStateEntity;
import com.bablsoft.accessflow.dashboard.internal.persistence.repo.DashboardDigestSubscriptionRepository;
import com.bablsoft.accessflow.dashboard.internal.persistence.repo.DashboardSuggestionStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DashboardInsightsIntegrationTest {

    private static final Instant IN_WEEK = Instant.parse("2026-06-15T10:00:00Z");
    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-30T00:00:00Z");

    @Autowired MyQueryInsightsLookupService insights;
    @Autowired DashboardDigestSubscriptionRepository digestRepo;
    @Autowired DashboardSuggestionStateRepository suggestionStateRepo;
    @Autowired AiAnalysisRepository aiAnalysisRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired CredentialEncryptionService encryptionService;

    private OrganizationEntity org;
    private UserEntity user;
    private DatasourceEntity datasource;

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
        cleanup();
        org = organizationRepository.save(newOrg());
        user = userRepository.save(newUser());
        datasource = datasourceRepository.save(newDatasource());
    }

    @AfterEach
    void cleanup() {
        suggestionStateRepo.deleteAll();
        digestRepo.deleteAll();
        // query_requests.ai_analysis_id ↔ ai_analyses.query_request_id is a circular FK; break the
        // query_requests → ai_analyses edge before deleting either table.
        queryRequestRepository.findAll().forEach(q -> {
            q.setAiAnalysisId(null);
            queryRequestRepository.save(q);
        });
        queryRequestRepository.flush();
        aiAnalysisRepository.deleteAll();
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void nativeQueriesRunAndScopeToTheUser() {
        // Query A: EXECUTED, non-failed HIGH analysis with one optimization.
        var a = saveQuery(QueryStatus.EXECUTED);
        var analysisA = saveAnalysis(a, RiskLevel.HIGH, false,
                "[{\"type\":\"INDEX\",\"title\":\"Add idx\",\"rationale\":\"speed\",\"sql\":\"CREATE INDEX\"}]");
        a.setAiAnalysisId(analysisA.getId());
        queryRequestRepository.save(a);
        // Query B: PENDING_REVIEW, no analysis.
        saveQuery(QueryStatus.PENDING_REVIEW);
        // Query C: PENDING_AI, failed analysis (excluded from risk + optimizations).
        var c = saveQuery(QueryStatus.PENDING_AI);
        var analysisC = saveAnalysis(c, RiskLevel.CRITICAL, true, "[{\"type\":\"REWRITE\"}]");
        c.setAiAnalysisId(analysisC.getId());
        queryRequestRepository.save(c);

        Map<QueryStatus, Long> counts = insights.statusCounts(org.getId(), user.getId()).stream()
                .collect(Collectors.toMap(MyQueryStatusCount::status, MyQueryStatusCount::count));
        assertThat(counts).containsEntry(QueryStatus.EXECUTED, 1L)
                .containsEntry(QueryStatus.PENDING_REVIEW, 1L)
                .containsEntry(QueryStatus.PENDING_AI, 1L);

        var trends = insights.trends(org.getId(), user.getId(), FROM, TO);
        assertThat(trends.statusByDay().stream().mapToLong(b -> b.count()).sum()).isEqualTo(3);
        // Only the non-failed analysis contributes to the risk series.
        assertThat(trends.riskByDay()).singleElement()
                .satisfies(b -> assertThat(b.riskLevel()).isEqualTo(RiskLevel.HIGH));

        var sources = insights.recentOptimizationSources(org.getId(), user.getId(), 10);
        assertThat(sources).singleElement().satisfies(s -> {
            assertThat(s.aiAnalysisId()).isEqualTo(analysisA.getId());
            assertThat(s.dbType()).isEqualTo(DbType.POSTGRESQL);
            assertThat(s.optimizationsJson()).contains("Add idx");
        });

        // Another user sees nothing.
        assertThat(insights.statusCounts(org.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void digestSubscriptionRoundTrips() {
        var sub = new DashboardDigestSubscriptionEntity();
        sub.setId(UUID.randomUUID());
        sub.setUserId(user.getId());
        sub.setOrganizationId(org.getId());
        sub.setEnabled(true);
        sub.setCreatedAt(IN_WEEK);
        sub.setUpdatedAt(IN_WEEK);
        digestRepo.save(sub);

        assertThat(digestRepo.findByUserId(user.getId())).isPresent();
        assertThat(digestRepo.findDue(Instant.now())).extracting(DashboardDigestSubscriptionEntity::getUserId)
                .contains(user.getId());
    }

    @Test
    void suggestionStateRoundTrips() {
        var a = saveQuery(QueryStatus.EXECUTED);
        var analysis = saveAnalysis(a, RiskLevel.LOW, false, "[]");
        var state = new DashboardSuggestionStateEntity();
        state.setId(UUID.randomUUID());
        state.setOrganizationId(org.getId());
        state.setUserId(user.getId());
        state.setAiAnalysisId(analysis.getId());
        state.setSuggestionIndex(0);
        state.setStatus(DashboardSuggestionStatus.DISMISSED);
        state.setCreatedAt(IN_WEEK);
        state.setUpdatedAt(IN_WEEK);
        suggestionStateRepo.save(state);

        assertThat(suggestionStateRepo.findByOrganizationIdAndUserIdAndAiAnalysisIdIn(
                org.getId(), user.getId(), java.util.List.of(analysis.getId()))).hasSize(1);
    }

    private QueryRequestEntity saveQuery(QueryStatus status) {
        var q = new QueryRequestEntity();
        q.setId(UUID.randomUUID());
        q.setDatasource(datasource);
        q.setSubmittedBy(user);
        q.setSqlText("SELECT 1");
        q.setQueryType(QueryType.SELECT);
        q.setStatus(status);
        q.setCreatedAt(IN_WEEK);
        return queryRequestRepository.save(q);
    }

    private AiAnalysisEntity saveAnalysis(QueryRequestEntity q, RiskLevel risk, boolean failed,
                                          String optimizations) {
        var a = new AiAnalysisEntity();
        a.setId(UUID.randomUUID());
        a.setQueryRequest(q);
        a.setAiProvider(AiProviderType.ANTHROPIC);
        a.setAiModel("claude-sonnet-4");
        a.setRiskScore(50);
        a.setRiskLevel(risk);
        a.setSummary("summary");
        a.setOptimizations(optimizations);
        a.setFailed(failed);
        a.setCreatedAt(IN_WEEK);
        return aiAnalysisRepository.save(a);
    }

    private OrganizationEntity newOrg() {
        var o = new OrganizationEntity();
        o.setId(UUID.randomUUID());
        o.setName("Primary");
        o.setSlug("primary-" + UUID.randomUUID());
        return o;
    }

    private UserEntity newUser() {
        var u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail("user-" + UUID.randomUUID() + "@example.com");
        u.setDisplayName("User");
        u.setPasswordHash("hash");
        u.setRole(UserRoleType.ANALYST);
        u.setAuthProvider(AuthProviderType.LOCAL);
        u.setActive(true);
        u.setOrganization(org);
        return u;
    }

    private DatasourceEntity newDatasource() {
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
        return ds;
    }
}
