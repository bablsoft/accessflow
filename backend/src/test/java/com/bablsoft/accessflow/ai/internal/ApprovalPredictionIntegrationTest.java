package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.TestSystemRoleSeeder;
import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.ai.api.ApprovalPredictionService;
import com.bablsoft.accessflow.ai.internal.persistence.repo.ApprovalPredictionModelRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.ApprovalPredictionLookupService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.events.ApprovalPredictionCompletedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the AF-651 serving path against real Postgres: train a per-org model from a
 * seeded decision history, score a query awaiting review, and assert the persisted row on the happy,
 * cold-start and late-estimate paths.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
@Import(ApprovalPredictionIntegrationTest.EventCollector.class)
class ApprovalPredictionIntegrationTest {

    /** Enough to clear the 50-sample floor and the 10-per-class floor with room to spare. */
    private static final int DECIDED_SAMPLES = 60;

    /**
     * Seeded timestamps deliberately carry a sub-microsecond component. {@code QueryRequestEntity}'s
     * {@code @Version} is an {@code Instant}: Postgres truncates it to microseconds while the JVM
     * holds nanoseconds, so any pre-loaded instance saved a second time fails the optimistic-lock
     * check. On Linux that happens with a plain {@code Instant.now()}; macOS often hands out
     * microsecond precision already and hides it. Forcing the nanosecond here makes both platforms
     * exercise the same path, so {@link #attachBackPointers}'s re-read is actually under test rather
     * than incidentally unnecessary.
     */
    private static final int SUB_MICROSECOND_NANOS = 1;

    @Autowired ApprovalPredictionService predictionService;
    @Autowired ApprovalPredictionLookupService predictionLookupService;
    @Autowired QueryRequestLookupService queryRequestLookupService;
    @Autowired ApprovalPredictionModelRepository modelRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired ReviewDecisionRepository reviewDecisionRepository;
    @Autowired AiAnalysisRepository aiAnalysisRepository;
    @Autowired QueryEstimateRepository queryEstimateRepository;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EventCollector events;

    /**
     * Plain {@code @EventListener}: {@code ApprovalPredictionCompletedEvent} is published inside the
     * scoring call, so it is observable as soon as a direct call returns — no {@code AFTER_COMMIT} hop.
     */
    @Component
    static class EventCollector {
        final List<ApprovalPredictionCompletedEvent> received = new CopyOnWriteArrayList<>();

        @EventListener
        void onPredictionCompleted(ApprovalPredictionCompletedEvent event) {
            received.add(event);
        }
    }

    private OrganizationEntity org;
    private UserEntity submitter;
    private UserEntity reviewer;
    private DatasourceEntity datasource;

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
        events.received.clear();
        org = saveOrg();
        submitter = saveUser("submitter");
        reviewer = saveUser("reviewer");
        datasource = saveDatasource();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("TRUNCATE TABLE organizations CASCADE");
        TestSystemRoleSeeder.reseedSystemRoles(jdbcTemplate);
    }

    @Test
    void trainsAServingModelThenScoresAQueryAwaitingReview() {
        seedDecisionHistory(DECIDED_SAMPLES);

        predictionService.trainAll();

        var model = modelRepository.findByOrganizationId(org.getId()).orElseThrow();
        assertThat(model.isServing()).isTrue();
        assertThat(model.getAuc()).isNotNull().isGreaterThanOrEqualTo(0.65);
        assertThat(model.getAccuracy()).isNotNull();
        assertThat(model.getTrainingSamples()).isEqualTo(DECIDED_SAMPLES);
        assertThat(model.getPositiveSamples()).isEqualTo(DECIDED_SAMPLES / 2);
        assertThat(model.getFeatureSchemaVersion())
                .isEqualTo(ApprovalFeatureVector.SCHEMA_VERSION);
        assertThat(model.getCoefficients()).isNotEqualTo("{}");

        var pending = seedPendingReview(20, true);
        events.received.clear();

        predictionService.predictForQuery(pending.getId());

        var prediction = predictionLookupService.findByQueryRequestId(pending.getId())
                .orElseThrow();
        assertThat(prediction.probability()).isNotNull().isBetween(0.0, 1.0);
        assertThat(prediction.modelId()).isEqualTo(model.getId());
        assertThat(prediction.featureSchemaVersion())
                .isEqualTo(ApprovalFeatureVector.SCHEMA_VERSION);
        assertThat(prediction.skipped()).isFalse();
        assertThat(prediction.failed()).isFalse();
        assertEstimateMissing(prediction.featuresJson(), false);
        assertThat(events.received).singleElement().satisfies(event -> {
            assertThat(event.queryRequestId()).isEqualTo(pending.getId());
            assertThat(event.predictionId()).isEqualTo(prediction.id());
            assertThat(event.probability()).isEqualTo(prediction.probability());
        });
    }

    /**
     * The coefficients blob is written and read through the <em>application</em>
     * {@code ObjectMapper}, which is configured {@code SNAKE_CASE}. The per-feature weight/mean/stddev
     * maps are keyed by literal feature names, so a naming strategy that touched map keys would make
     * {@code predict} throw "Missing weight for feature" — assert the round trip through the real bean.
     */
    @Test
    void storesCoefficientsThatRoundTripThroughTheApplicationObjectMapper() {
        seedDecisionHistory(DECIDED_SAMPLES);
        predictionService.trainAll();

        var model = modelRepository.findByOrganizationId(org.getId()).orElseThrow();
        var trained = TrainedApprovalModel.fromJson(model.getCoefficients(), objectMapper);

        assertThat(trained.featureNames()).isEqualTo(ApprovalFeatureVector.FEATURE_SCHEMA_V1);
        assertThat(trained.weights()).containsKeys("risk_level_LOW", "submitter_approval_rate");
        assertThat(trained.stddevs().values()).allSatisfy(stddev ->
                assertThat(stddev).isNotNull().isNotEqualTo(0.0));
        var probability = trained.predict(
                new double[ApprovalFeatureVector.FEATURE_SCHEMA_V1.size()]);
        assertThat(probability).isBetween(0.0, 1.0);
    }

    /**
     * The read side (AF-653) joins the prediction onto the query detail without a reciprocal FK on
     * {@code query_requests}, so only a real-schema run proves the extra lookup actually resolves.
     */
    @Test
    void queryDetailCarriesTheScoredPredictionAndTheBatchLookupFindsIt() {
        seedDecisionHistory(DECIDED_SAMPLES);
        predictionService.trainAll();
        var pending = seedPendingReview(20, true);

        predictionService.predictForQuery(pending.getId());

        var detail = queryRequestLookupService.findDetailById(pending.getId(), org.getId())
                .orElseThrow();
        var persisted = predictionLookupService.findByQueryRequestId(pending.getId())
                .orElseThrow();
        assertThat(detail.approvalPrediction()).isNotNull();
        assertThat(detail.approvalPrediction().id()).isEqualTo(persisted.id());
        assertThat(detail.approvalPrediction().probability()).isEqualTo(persisted.probability());
        assertThat(detail.approvalPrediction().skipped()).isFalse();
        assertThat(detail.approvalPrediction().failed()).isFalse();
        assertThat(detail.approvalPrediction().createdAt()).isNotNull();

        assertThat(predictionLookupService.findByQueryRequestIds(List.of(pending.getId())))
                .singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.queryRequestId()).isEqualTo(pending.getId());
                    assertThat(snapshot.probability()).isEqualTo(persisted.probability());
                });
    }

    @Test
    void queryDetailLeavesTheApprovalPredictionNullBeforeScoring() {
        var pending = seedPendingReview(20, true);

        var detail = queryRequestLookupService.findDetailById(pending.getId(), org.getId())
                .orElseThrow();

        assertThat(detail.approvalPrediction()).isNull();
    }

    @Test
    void skipsWithModelNotServingBeforeTheOrgHasEnoughHistory() {
        seedDecisionHistory(10);

        predictionService.trainAll();

        var model = modelRepository.findByOrganizationId(org.getId()).orElseThrow();
        assertThat(model.isServing()).isFalse();
        assertThat(model.getTrainingSamples()).isEqualTo(10);
        assertThat(model.getCoefficients()).isEqualTo("{}");

        var pending = seedPendingReview(20, true);
        predictionService.predictForQuery(pending.getId());

        var prediction = predictionLookupService.findByQueryRequestId(pending.getId())
                .orElseThrow();
        assertThat(prediction.skipped()).isTrue();
        assertThat(prediction.skippedReason()).isEqualTo("MODEL_NOT_SERVING");
        assertThat(prediction.probability()).isNull();
        assertThat(prediction.featuresJson()).isNull();
    }

    @Test
    void replacesThePredictionInPlaceWhenTheCostEstimateArrivesLate() {
        seedDecisionHistory(DECIDED_SAMPLES);
        predictionService.trainAll();
        var pending = seedPendingReview(20, false);

        predictionService.predictForQuery(pending.getId());

        var first = predictionLookupService.findByQueryRequestId(pending.getId()).orElseThrow();
        assertEstimateMissing(first.featuresJson(), true);

        attachEstimate(pending);
        predictionService.refreshForLateEstimate(pending.getId());

        var second = predictionLookupService.findByQueryRequestId(pending.getId()).orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertEstimateMissing(second.featuresJson(), false);
        assertThat(second.probability()).isNotEqualTo(first.probability());
    }

    /**
     * Postgres normalises JSONB on read (reordered keys, a space after each colon), so the snapshot
     * has to be parsed rather than string-matched. Asserting the node <em>type</em> is the point:
     * {@code DefaultApprovalPredictionPersistenceService} gates its replace path on
     * {@code path("estimate_missing").asBoolean(false)}, which coerces a JSON number to
     * {@code false} and would silently disable re-scoring forever.
     */
    private void assertEstimateMissing(String featuresJson, boolean expected) {
        var node = objectMapper.readTree(featuresJson).path("estimate_missing");
        assertThat(node.isBoolean()).isTrue();
        assertThat(node.asBoolean(!expected)).isEqualTo(expected);
    }

    @Test
    void leavesThePredictionAloneWhenNothingIsWaitingOnALateEstimate() {
        seedDecisionHistory(DECIDED_SAMPLES);
        predictionService.trainAll();
        var pending = seedPendingReview(20, true);
        predictionService.predictForQuery(pending.getId());
        var scored = predictionLookupService.findByQueryRequestId(pending.getId()).orElseThrow();

        predictionService.refreshForLateEstimate(pending.getId());

        var after = predictionLookupService.findByQueryRequestId(pending.getId()).orElseThrow();
        assertThat(after.probability()).isEqualTo(scored.probability());
        assertThat(after.featuresJson()).isEqualTo(scored.featuresJson());
    }

    @Test
    void theReadyForReviewListenerScoresTheQueryAfterCommit() {
        seedDecisionHistory(DECIDED_SAMPLES);
        predictionService.trainAll();
        var pending = seedPendingReview(20, true);

        // @ApplicationModuleListener is AFTER_COMMIT, so the publish needs a transaction to commit.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new QueryReadyForReviewEvent(pending.getId())));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(predictionLookupService.findByQueryRequestId(pending.getId()))
                        .get()
                        .satisfies(prediction -> {
                            assertThat(prediction.probability()).isNotNull();
                            assertThat(prediction.skipped()).isFalse();
                        }));

        // The event fans out to the audit module too, and its listener inserts an audit_log row that
        // holds a foreign-key lock on organizations. Waiting for it keeps the next test's
        // TRUNCATE ... CASCADE from deadlocking against an async transaction still in flight.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM audit_log WHERE resource_id = ?"
                                + " AND action = 'QUERY_REVIEW_REQUESTED')",
                        Boolean.class, pending.getId())));
    }

    // ------------------------------------------------------------------------ seeding

    /**
     * Seeds {@code count} human-decided queries whose label alternates by index and whose AI risk
     * score is the separating signal (low risk approved, high risk rejected), so the holdout AUC is
     * high for the right reason. Ids come from a fixed seed because the holdout split hashes the
     * query UUID — a random id would make the split, and therefore the metrics, vary run to run.
     *
     * <p>Two thirds of the history also carries a cost estimate, with the blast radius tracking the
     * outcome. Without that variation the four estimate features would be constant across the
     * training set, the trainer would standardize them to zero and leave their weights at zero, and
     * the late-estimate re-score would provably be unable to move a probability.
     */
    private void seedDecisionHistory(int count) {
        var base = Instant.now().minus(Duration.ofDays(60)).plusNanos(SUB_MICROSECOND_NANOS);
        for (int i = 0; i < count; i++) {
            boolean approved = i % 2 == 0;
            var query = new QueryRequestEntity();
            query.setId(UUID.nameUUIDFromBytes(("af651-history-" + i).getBytes()));
            query.setDatasource(datasource);
            query.setSubmittedBy(submitter);
            query.setSqlText("SELECT " + i);
            query.setQueryType(QueryType.SELECT);
            query.setStatus(approved ? QueryStatus.EXECUTED : QueryStatus.REJECTED);
            query.setSubmissionReason(SubmissionReason.USER_SUBMITTED);
            query.setCreatedAt(base.plusSeconds(i * 3600L));
            query.setUpdatedAt(base.plusSeconds(i * 3600L));
            query = queryRequestRepository.save(query);

            seedDecision(query, approved ? DecisionType.APPROVED : DecisionType.REJECTED);
            var analysisId = seedAnalysis(query, approved ? 10 : 90,
                    approved ? RiskLevel.LOW : RiskLevel.HIGH);
            var estimateId = i % 3 != 0
                    ? seedEstimate(query, approved ? 50L : 5_000_000L,
                            approved ? 1.5 : 90_000.0, approved ? "Index Scan" : "Seq Scan")
                    : null;
            attachBackPointers(query.getId(), analysisId, estimateId);
        }
    }

    /** A query awaiting review; {@code withEstimate} controls whether the cost estimate is present. */
    private QueryRequestEntity seedPendingReview(int riskScore, boolean withEstimate) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(datasource);
        query.setSubmittedBy(submitter);
        query.setSqlText("SELECT pending");
        query.setQueryType(QueryType.SELECT);
        query.setStatus(QueryStatus.PENDING_REVIEW);
        query.setSubmissionReason(SubmissionReason.USER_SUBMITTED);
        var submittedAt = Instant.now().plusNanos(SUB_MICROSECOND_NANOS);
        query.setCreatedAt(submittedAt);
        query.setUpdatedAt(submittedAt);
        query = queryRequestRepository.save(query);
        var analysisId = seedAnalysis(query, riskScore, RiskLevel.LOW);
        var estimateId = withEstimate ? seedEstimate(query) : null;
        return attachBackPointers(query.getId(), analysisId, estimateId);
    }

    /** Attaches a cost estimate to a query that was scored without one. */
    private void attachEstimate(QueryRequestEntity query) {
        var estimateId = seedEstimate(query);
        var reloaded = queryRequestRepository.findById(query.getId()).orElseThrow();
        reloaded.setQueryEstimateId(estimateId);
        queryRequestRepository.save(reloaded);
    }

    /**
     * Sets the {@code ai_analysis_id} / {@code query_estimate_id} back-pointers on a re-read
     * instance. The re-read is load-bearing: {@code QueryRequestEntity}'s {@code @Version} is an
     * {@code Instant}, Postgres truncates it to microseconds while the JVM holds nanoseconds, so a
     * pre-loaded instance's version stops matching the stored one once the child inserts have
     * flushed — an optimistic-lock failure that shows up on Linux CI and not on macOS.
     */
    private QueryRequestEntity attachBackPointers(UUID queryId, UUID analysisId, UUID estimateId) {
        var reloaded = queryRequestRepository.findById(queryId).orElseThrow();
        reloaded.setAiAnalysisId(analysisId);
        reloaded.setQueryEstimateId(estimateId);
        return queryRequestRepository.save(reloaded);
    }

    private OrganizationEntity saveOrg() {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("Approval Prediction Org");
        entity.setSlug("apo-" + UUID.randomUUID());
        return organizationRepository.save(entity);
    }

    private UserEntity saveUser(String prefix) {
        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        entity.setDisplayName(prefix);
        entity.setPasswordHash("hash");
        entity.setRole(UserRoleType.ANALYST);
        entity.setAuthProvider(AuthProviderType.LOCAL);
        entity.setActive(true);
        entity.setOrganization(org);
        return userRepository.save(entity);
    }

    /** No review plan is attached, so {@code QueryTimeoutJob} can never flip the fixture. */
    private DatasourceEntity saveDatasource() {
        var entity = new DatasourceEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(org);
        entity.setName("DS-" + UUID.randomUUID());
        entity.setDbType(DbType.POSTGRESQL);
        entity.setHost("nope.invalid");
        entity.setPort(65000);
        entity.setDatabaseName("db");
        entity.setUsername("u");
        entity.setPasswordEncrypted("ENC");
        entity.setAiAnalysisEnabled(false);
        entity.setActive(true);
        entity.setCreatedAt(Instant.now());
        return datasourceRepository.save(entity);
    }

    private void seedDecision(QueryRequestEntity query, DecisionType decision) {
        var entity = new ReviewDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequest(query);
        entity.setReviewer(reviewer);
        entity.setDecision(decision);
        entity.setStage(1);
        entity.setDecidedAt(query.getCreatedAt());
        reviewDecisionRepository.save(entity);
    }

    private UUID seedAnalysis(QueryRequestEntity query, int riskScore, RiskLevel riskLevel) {
        var entity = new AiAnalysisEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequest(query);
        entity.setAiProvider(AiProviderType.OPENAI);
        entity.setAiModel("gpt-4o-mock");
        entity.setRiskScore(riskScore);
        entity.setRiskLevel(riskLevel);
        entity.setSummary("seeded");
        entity.setIssues("[{\"code\":\"X\"}]");
        entity.setFailed(false);
        entity.setCreatedAt(query.getCreatedAt());
        return aiAnalysisRepository.save(entity).getId();
    }

    private UUID seedEstimate(QueryRequestEntity query) {
        return seedEstimate(query, 1_000L, 42.5, "Seq Scan");
    }

    private UUID seedEstimate(QueryRequestEntity query, long estimatedRows, double estimatedCost,
                              String scanType) {
        var entity = new QueryEstimateEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequest(query);
        entity.setEngineId("postgresql");
        entity.setQueryType(QueryType.SELECT);
        entity.setSupported(true);
        entity.setEstimatedRows(estimatedRows);
        entity.setAffectedRowCount(null);
        entity.setEstimatedCost(estimatedCost);
        entity.setScanType(scanType);
        entity.setFailed(false);
        entity.setCreatedAt(query.getCreatedAt() == null ? Instant.now() : query.getCreatedAt());
        return queryEstimateRepository.save(entity).getId();
    }
}
