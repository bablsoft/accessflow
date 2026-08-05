package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.ApprovalOutcomeHistoryLookupService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryEstimateEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewDecisionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryEstimateRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewDecisionRepository;
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
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the AF-649 training-label filter against real Postgres: one seeded query per label
 * category, asserting exactly the human-decided ones come back with the right labels, features
 * and org scoping.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApprovalOutcomeHistoryLookupIntegrationTest {

    @Autowired ApprovalOutcomeHistoryLookupService lookupService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired ReviewDecisionRepository reviewDecisionRepository;
    @Autowired AiAnalysisRepository aiAnalysisRepository;
    @Autowired QueryEstimateRepository queryEstimateRepository;

    private final Instant since = Instant.parse("2026-07-01T00:00:00Z");
    private OrganizationEntity orgA;
    private OrganizationEntity orgB;
    private UserEntity submitterA;
    private DatasourceEntity dsA;
    private QueryRequestEntity humanApproved;
    private QueryRequestEntity humanRejected;
    private QueryRequestEntity timedOut;

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
        submitterA = saveUser(orgA, "submitter@a.test");
        var reviewerA = saveUser(orgA, "reviewer@a.test");
        var submitterB = saveUser(orgB, "submitter@b.test");
        var reviewerB = saveUser(orgB, "reviewer@b.test");
        dsA = saveDatasource(orgA, "dsA");
        var dsB = saveDatasource(orgB, "dsB");

        // 1. human-approved (newest): decision row + AI + estimate — the full-feature positive.
        humanApproved = seedQuery(dsA, submitterA, QueryStatus.EXECUTED,
                Instant.parse("2026-07-20T10:00:00Z"), SubmissionReason.USER_SUBMITTED, null);
        seedDecision(humanApproved, reviewerA, DecisionType.APPROVED);
        // Single re-save for both back-pointers — a second stale-version save would trip the
        // @Version optimistic lock.
        humanApproved.setAiAnalysisId(
                seedAnalysis(humanApproved, 42, RiskLevel.HIGH, "[{\"code\":\"X\"}]", false));
        humanApproved.setQueryEstimateId(
                seedEstimate(humanApproved, true, 100L, 50L, 12.5, "Seq Scan", false));
        humanApproved = queryRequestRepository.save(humanApproved);

        // 2. human-rejected: decision row, no AI/estimate — negative with both missing flags.
        humanRejected = seedQuery(dsA, submitterA, QueryStatus.REJECTED,
                Instant.parse("2026-07-15T10:00:00Z"), SubmissionReason.USER_SUBMITTED, null);
        seedDecision(humanRejected, reviewerA, DecisionType.REJECTED);

        // 3. timed-out: the one legitimate zero-decision negative.
        timedOut = seedQuery(dsA, submitterA, QueryStatus.TIMED_OUT,
                Instant.parse("2026-07-10T10:00:00Z"), SubmissionReason.USER_SUBMITTED, null);

        // 4. cancelled: excluded by status even though a decision row exists.
        var cancelled = seedQuery(dsA, submitterA, QueryStatus.CANCELLED,
                Instant.parse("2026-07-09T10:00:00Z"), SubmissionReason.USER_SUBMITTED, null);
        seedDecision(cancelled, reviewerA, DecisionType.REQUESTED_CHANGES);

        // 5. grant-covered: excluded by approved_by_grant_id alone (decision row present so the
        // exclusion is attributable to the grant predicate, not the exists check).
        var grantCovered = seedQuery(dsA, submitterA, QueryStatus.APPROVED,
                Instant.parse("2026-07-08T10:00:00Z"), SubmissionReason.USER_SUBMITTED,
                UUID.randomUUID());
        seedDecision(grantCovered, reviewerA, DecisionType.APPROVED);

        // 6. break-glass: excluded by submission_reason alone.
        var breakGlass = seedQuery(dsA, submitterA, QueryStatus.EXECUTED,
                Instant.parse("2026-07-07T10:00:00Z"), SubmissionReason.EMERGENCY_ACCESS, null);
        seedDecision(breakGlass, reviewerA, DecisionType.APPROVED);

        // 7. routing-auto / external-ticket signature: terminal APPROVED with zero decision rows.
        seedQuery(dsA, submitterA, QueryStatus.APPROVED,
                Instant.parse("2026-07-06T10:00:00Z"), SubmissionReason.USER_SUBMITTED, null);

        // 8. out-of-window: excluded by created_at < since.
        var outOfWindow = seedQuery(dsA, submitterA, QueryStatus.EXECUTED,
                Instant.parse("2026-06-15T10:00:00Z"), SubmissionReason.USER_SUBMITTED, null);
        seedDecision(outOfWindow, reviewerA, DecisionType.APPROVED);

        // 9. other-org: must never leak into orgA's samples or counts.
        var otherOrg = seedQuery(dsB, submitterB, QueryStatus.EXECUTED,
                Instant.parse("2026-07-05T10:00:00Z"), SubmissionReason.USER_SUBMITTED, null);
        seedDecision(otherOrg, reviewerB, DecisionType.APPROVED);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void returnsExactlyTheHumanDecidedQueriesNewestFirstWithLabels() {
        var samples = lookupService.findDecidedSamples(orgA.getId(), since, 50);

        assertThat(samples).extracting("queryRequestId").containsExactly(
                humanApproved.getId(), humanRejected.getId(), timedOut.getId());
        assertThat(samples).extracting("approved").containsExactly(true, false, false);
    }

    @Test
    void mapsFeatureColumnsOnTheFullFeatureSample() {
        var sample = lookupService.findDecidedSamples(orgA.getId(), since, 50).get(0);

        assertThat(sample.queryRequestId()).isEqualTo(humanApproved.getId());
        assertThat(sample.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(sample.transactional()).isFalse();
        assertThat(sample.createdAt()).isEqualTo(Instant.parse("2026-07-20T10:00:00Z"));
        assertThat(sample.submitterId()).isEqualTo(submitterA.getId());
        assertThat(sample.datasourceId()).isEqualTo(dsA.getId());
        assertThat(sample.aiMissing()).isFalse();
        assertThat(sample.aiRiskScore()).isEqualTo(42);
        assertThat(sample.aiRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(sample.aiIssueCount()).isEqualTo(1);
        assertThat(sample.estimateMissing()).isFalse();
        assertThat(sample.estimatedRows()).isEqualTo(100L);
        assertThat(sample.affectedRowCount()).isEqualTo(50L);
        assertThat(sample.estimatedCost()).isEqualTo(12.5);
        assertThat(sample.scanType()).isEqualTo("Seq Scan");
    }

    @Test
    void flagsMissingAiAndEstimateOnSamplesWithoutLinkedRows() {
        var samples = lookupService.findDecidedSamples(orgA.getId(), since, 50);

        assertThat(samples.subList(1, 3)).allSatisfy(sample -> {
            assertThat(sample.aiMissing()).isTrue();
            assertThat(sample.aiRiskScore()).isNull();
            assertThat(sample.aiRiskLevel()).isNull();
            assertThat(sample.aiIssueCount()).isNull();
            assertThat(sample.estimateMissing()).isTrue();
            assertThat(sample.estimatedRows()).isNull();
            assertThat(sample.affectedRowCount()).isNull();
            assertThat(sample.estimatedCost()).isNull();
            assertThat(sample.scanType()).isNull();
        });
    }

    @Test
    void capsResultsAtMaxRowsKeepingTheNewest() {
        var samples = lookupService.findDecidedSamples(orgA.getId(), since, 1);

        assertThat(samples).extracting("queryRequestId")
                .containsExactly(humanApproved.getId());
    }

    @Test
    void countsDecidedAndApprovedForSubmitterAndDatasource() {
        assertThat(lookupService.submitterCounts(orgA.getId(), submitterA.getId(), since))
                .satisfies(counts -> {
                    assertThat(counts.decided()).isEqualTo(3);
                    assertThat(counts.approved()).isEqualTo(1);
                });
        assertThat(lookupService.datasourceCounts(orgA.getId(), dsA.getId(), since))
                .satisfies(counts -> {
                    assertThat(counts.decided()).isEqualTo(3);
                    assertThat(counts.approved()).isEqualTo(1);
                });
    }

    @Test
    void doesNotLeakOtherOrganizationsQueries() {
        var samples = lookupService.findDecidedSamples(orgB.getId(), since, 50);

        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).datasourceId()).isNotEqualTo(dsA.getId());
        assertThat(lookupService.datasourceCounts(orgB.getId(),
                samples.get(0).datasourceId(), since))
                .satisfies(counts -> {
                    assertThat(counts.decided()).isEqualTo(1);
                    assertThat(counts.approved()).isEqualTo(1);
                });
    }

    private void cleanup() {
        // query_requests holds restrict FKs to ai_analyses / query_estimates via the bare
        // back-pointer columns — null them first, then delete children before parents.
        var requests = queryRequestRepository.findAll();
        requests.forEach(q -> {
            q.setAiAnalysisId(null);
            q.setQueryEstimateId(null);
        });
        queryRequestRepository.saveAll(requests);
        reviewDecisionRepository.deleteAll();
        aiAnalysisRepository.deleteAll();
        queryEstimateRepository.deleteAll();
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

    private QueryRequestEntity seedQuery(DatasourceEntity ds, UserEntity submitter,
                                         QueryStatus status, Instant when,
                                         SubmissionReason reason, UUID grantId) {
        var qr = new QueryRequestEntity();
        qr.setId(UUID.randomUUID());
        qr.setDatasource(ds);
        qr.setSubmittedBy(submitter);
        qr.setSqlText("SELECT 1");
        qr.setQueryType(QueryType.SELECT);
        qr.setStatus(status);
        qr.setSubmissionReason(reason);
        qr.setApprovedByGrantId(grantId);
        qr.setCreatedAt(when);
        qr.setUpdatedAt(when);
        return queryRequestRepository.save(qr);
    }

    private void seedDecision(QueryRequestEntity qr, UserEntity reviewer, DecisionType decision) {
        var d = new ReviewDecisionEntity();
        d.setId(UUID.randomUUID());
        d.setQueryRequest(qr);
        d.setReviewer(reviewer);
        d.setDecision(decision);
        d.setStage(1);
        d.setDecidedAt(qr.getCreatedAt());
        reviewDecisionRepository.save(d);
    }

    private UUID seedAnalysis(QueryRequestEntity qr, int riskScore, RiskLevel riskLevel,
                              String issues, boolean failed) {
        var a = new AiAnalysisEntity();
        a.setId(UUID.randomUUID());
        a.setQueryRequest(qr);
        a.setAiProvider(AiProviderType.OPENAI);
        a.setAiModel("gpt-4o-mock");
        a.setRiskScore(riskScore);
        a.setRiskLevel(riskLevel);
        a.setSummary("seeded");
        a.setIssues(issues);
        a.setFailed(failed);
        a.setCreatedAt(qr.getCreatedAt());
        return aiAnalysisRepository.save(a).getId();
    }

    private UUID seedEstimate(QueryRequestEntity qr, boolean supported, Long estimatedRows,
                              Long affectedRowCount, Double estimatedCost, String scanType,
                              boolean failed) {
        var e = new QueryEstimateEntity();
        e.setId(UUID.randomUUID());
        e.setQueryRequest(qr);
        e.setEngineId("postgresql");
        e.setQueryType(qr.getQueryType());
        e.setSupported(supported);
        e.setEstimatedRows(estimatedRows);
        e.setAffectedRowCount(affectedRowCount);
        e.setEstimatedCost(estimatedCost);
        e.setScanType(scanType);
        e.setFailed(failed);
        e.setCreatedAt(qr.getCreatedAt());
        return queryEstimateRepository.save(e).getId();
    }
}
