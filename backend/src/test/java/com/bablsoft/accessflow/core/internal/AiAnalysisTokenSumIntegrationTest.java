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
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
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
 * Validates the AF-55 monthly token-budget native query against real Postgres: org-scoped,
 * {@code created_at >= since}, and COALESCE-to-0 when no rows match.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AiAnalysisTokenSumIntegrationTest {

    @Autowired AiAnalysisStatsLookupService statsLookupService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired AiAnalysisRepository aiAnalysisRepository;

    private final Instant since = Instant.parse("2026-06-01T00:00:00Z");
    private OrganizationEntity orgA;
    private OrganizationEntity orgB;

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
        orgA = saveOrg("A");
        orgB = saveOrg("B");
        var userA = saveUser(orgA, "a@a.test");
        var userB = saveUser(orgB, "b@b.test");
        var dsA = saveDatasource(orgA, "dsA");
        var dsB = saveDatasource(orgB, "dsB");

        // orgA: two rows in-window (150 + 220 = 370) and one before the cutoff (excluded).
        seed(dsA, userA, Instant.parse("2026-06-05T10:00:00Z"), 100, 50);
        seed(dsA, userA, Instant.parse("2026-06-10T10:00:00Z"), 200, 20);
        seed(dsA, userA, Instant.parse("2026-05-20T10:00:00Z"), 999, 999);
        // orgB: a large row that must never leak into orgA's sum.
        seed(dsB, userB, Instant.parse("2026-06-07T10:00:00Z"), 5000, 5000);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void sumsOnlyInWindowTokensForTheOrganization() {
        assertThat(statsLookupService.sumTokensSince(orgA.getId(), since)).isEqualTo(370L);
    }

    @Test
    void doesNotLeakOtherOrganizationsTokens() {
        assertThat(statsLookupService.sumTokensSince(orgB.getId(), since)).isEqualTo(10_000L);
    }

    @Test
    void returnsZeroWhenNoRowsMatch() {
        assertThat(statsLookupService.sumTokensSince(orgA.getId(),
                Instant.parse("2027-01-01T00:00:00Z"))).isZero();
    }

    private void cleanup() {
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

    private UserEntity saveUser(OrganizationEntity org, String email) {
        var u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(email);
        u.setPasswordHash("hashed");
        u.setRole(UserRoleType.ANALYST);
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

    private void seed(DatasourceEntity ds, UserEntity submitter, Instant when,
                      int promptTokens, int completionTokens) {
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
        a.setRiskScore(10);
        a.setRiskLevel(RiskLevel.LOW);
        a.setSummary("seeded");
        a.setIssues("[]");
        a.setFailed(false);
        a.setPromptTokens(promptTokens);
        a.setCompletionTokens(completionTokens);
        a.setCreatedAt(when.truncatedTo(ChronoUnit.SECONDS));
        aiAnalysisRepository.save(a);
    }
}
