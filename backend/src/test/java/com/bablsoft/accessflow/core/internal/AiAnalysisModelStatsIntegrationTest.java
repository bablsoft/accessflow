package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AiAnalysisStatsLookupService;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisModelResultEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisModelResultRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the AF-450 per-model stats native query against real Postgres: it groups
 * {@code ai_analysis_model_result} by (provider, model), sums tokens, averages latency over all
 * members, and averages risk over non-failed members only, scoped to the organization/window.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AiAnalysisModelStatsIntegrationTest {

    @Autowired AiAnalysisStatsLookupService statsLookupService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired AiAnalysisRepository aiAnalysisRepository;
    @Autowired AiAnalysisModelResultRepository modelResultRepository;

    private final Instant from = Instant.parse("2026-06-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-07-01T00:00:00Z");
    private OrganizationEntity org;

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
        cleanup();
        org = saveOrg("A");
        var user = saveUser(org, "a@a.test");
        var ds = saveDatasource(org, "dsA");

        // Two analyses in-window; claude appears in both (one failed), llama once.
        var a1 = seedAnalysis(ds, user, Instant.parse("2026-06-05T10:00:00Z"));
        seedModel(a1, AiProviderType.ANTHROPIC, "claude", 60, RiskLevel.HIGH, 100, 40, 800, false);
        seedModel(a1, AiProviderType.OLLAMA, "llama", 40, RiskLevel.MEDIUM, 50, 10, 200, false);

        var a2 = seedAnalysis(ds, user, Instant.parse("2026-06-10T10:00:00Z"));
        seedModel(a2, AiProviderType.ANTHROPIC, "claude", null, null, 80, 20, 1200, true);

        // Out-of-window analysis must be excluded.
        var old = seedAnalysis(ds, user, Instant.parse("2026-05-01T10:00:00Z"));
        seedModel(old, AiProviderType.ANTHROPIC, "claude", 90, RiskLevel.CRITICAL, 999, 999, 9999, false);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void groupsByModelSummingTokensAveragingLatencyAndRiskOverSuccessesOnly() {
        var stats = statsLookupService.query(org.getId(), from, to, null).perModelStats();

        var claude = stats.stream().filter(m -> m.model().equals("claude")).findFirst().orElseThrow();
        assertThat(claude.provider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(claude.analysisCount()).isEqualTo(2L); // both in-window members
        assertThat(claude.totalPromptTokens()).isEqualTo(180L); // 100 + 80
        assertThat(claude.totalCompletionTokens()).isEqualTo(60L); // 40 + 20
        // latency averaged over both (800 + 1200) / 2 = 1000
        assertThat(claude.avgLatencyMs().doubleValue()).isEqualTo(1000.0);
        // risk averaged over the single non-failed member only = 60
        assertThat(claude.avgRiskScore().doubleValue()).isEqualTo(60.0);

        var llama = stats.stream().filter(m -> m.model().equals("llama")).findFirst().orElseThrow();
        assertThat(llama.analysisCount()).isEqualTo(1L);
        assertThat(llama.totalPromptTokens()).isEqualTo(50L);
    }

    private void cleanup() {
        modelResultRepository.deleteAll();
        aiAnalysisRepository.deleteAll();
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private OrganizationEntity saveOrg(String name) {
        var o = new OrganizationEntity();
        o.setId(UUID.randomUUID());
        o.setName(name);
        o.setSlug(name.toLowerCase() + "-" + UUID.randomUUID());
        return organizationRepository.save(o);
    }

    private UserEntity saveUser(OrganizationEntity organization, String email) {
        var u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(email);
        u.setPasswordHash("hashed");
        u.setRole(UserRoleType.ANALYST);
        u.setAuthProvider(AuthProviderType.LOCAL);
        u.setActive(true);
        u.setOrganization(organization);
        return userRepository.save(u);
    }

    private DatasourceEntity saveDatasource(OrganizationEntity organization, String name) {
        var d = new DatasourceEntity();
        d.setId(UUID.randomUUID());
        d.setOrganization(organization);
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

    private AiAnalysisEntity seedAnalysis(DatasourceEntity ds, UserEntity submitter, Instant when) {
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
        a.setAiProvider(AiProviderType.ANTHROPIC);
        a.setAiModel("aggregate");
        a.setRiskScore(50);
        a.setRiskLevel(RiskLevel.MEDIUM);
        a.setSummary("seeded");
        a.setIssues("[]");
        a.setFailed(false);
        a.setPromptTokens(0);
        a.setCompletionTokens(0);
        a.setCreatedAt(when.truncatedTo(ChronoUnit.SECONDS));
        return aiAnalysisRepository.save(a);
    }

    private void seedModel(AiAnalysisEntity analysis, AiProviderType provider, String model,
                           Integer risk, RiskLevel level, int promptTokens, int completionTokens,
                           long latencyMs, boolean failed) {
        var m = new AiAnalysisModelResultEntity();
        m.setId(UUID.randomUUID());
        m.setAiAnalysisId(analysis.getId());
        m.setAiProvider(provider);
        m.setAiModel(model);
        m.setRiskScore(risk);
        m.setRiskLevel(level);
        m.setWeight(1.0);
        m.setPromptTokens(promptTokens);
        m.setCompletionTokens(completionTokens);
        m.setLatencyMs(latencyMs);
        m.setFailed(failed);
        modelResultRepository.save(m);
    }
}
