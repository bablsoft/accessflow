package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsService;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.api.LeadTimeSource;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentReviewDecisionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentReviewDecisionRepository;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Runs the #984 metrics against real PostgreSQL over a hand-computed fixture, window
 * {@code [2026-10-01, 2026-10-08)}:
 * <pre>
 *   prod    A exec 10-01 10:00 SUCCEEDED   lead 7200 (commit_timestamp)  review 600s
 *   prod    B exec 10-02 10:00 FAILED      lead 1800 (malformed commit → created_at), reported 11:00
 *   prod    C exec 10-02 12:00 SUCCEEDED   lead 1800, restores B in 3600s, review 900s (2 decisions)
 *   prod    D exec 10-03 23:30 ROLLED_BACK lead 1800, reported 23:45, never restored
 *   staging E exec 10-04 10:00 unreported  lead 3600
 *   F routing auto-approval · G break-glass (review row planted, still excluded) · H TIMED_OUT
 *   · I legacy TIMED_OUT (no review_started_at) · J legacy REJECTED with a decision row
 *   · Z executed 09-30, outside the window
 * </pre>
 *
 * <p>No {@code deleteAll} cleanup: every identifier is randomized, so rows left in the shared
 * container never collide with other classes.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeploymentMetricsIntegrationTest {

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-08T00:00:00Z");
    private static final String API_KEY_HEADER = "X-API-Key";

    @Autowired WebApplicationContext context;
    @Autowired DeploymentMetricsService metricsService;
    @Autowired DeploymentRequestStateService stateService;
    @Autowired DeploymentPipelineRepository pipelineRepository;
    @Autowired DeploymentEnvironmentRepository environmentRepository;
    @Autowired DeploymentRequestRepository requestRepository;
    @Autowired DeploymentReviewDecisionRepository decisionRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApiKeyService apiKeyService;
    @Autowired @Qualifier("transactionManager") PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;

    private MockMvcTester mvc;
    private TransactionTemplate transactionTemplate;
    private OrganizationEntity org;
    private UserEntity submitter;
    private UserEntity reviewer;
    private DeploymentPipelineEntity pipeline;
    private DeploymentEnvironmentEntity prod;
    private DeploymentEnvironmentEntity staging;
    private DeploymentRequestEntity rolledBack;

    @BeforeEach
    void seed() {
        mvc = MockMvcTester.from(context, b -> b.apply(springSecurity()).build());
        // The app's JPA manager — the only TransactionTemplate bean is the audit datasource's.
        transactionTemplate = new TransactionTemplate(transactionManager);
        org = organization();
        submitter = user(UserRoleType.ANALYST);
        reviewer = user(UserRoleType.REVIEWER);
        pipeline = pipeline();
        prod = environment("prod", 1);
        staging = environment("staging", 0);

        var a = executed(prod, "1.0.0", "2026-10-01T09:00:00Z", "2026-10-01T10:00:00Z",
                DeploymentOutcome.SUCCEEDED, "2026-10-01T10:05:00Z",
                "{\"commit_timestamp\": \"2026-10-01T08:00:00Z\"}");
        reviewed(a, "2026-10-01T09:05:00Z", "2026-10-01T09:15:00Z");
        executed(prod, "1.0.1", "2026-10-02T09:30:00Z", "2026-10-02T10:00:00Z",
                DeploymentOutcome.FAILED, "2026-10-02T11:00:00Z",
                "{\"commit_timestamp\": \"not-a-date\"}");
        var c = executed(prod, "1.0.2", "2026-10-02T11:30:00Z", "2026-10-02T12:00:00Z",
                DeploymentOutcome.SUCCEEDED, "2026-10-02T12:05:00Z", "{}");
        reviewed(c, "2026-10-02T11:35:00Z", "2026-10-02T11:40:00Z", "2026-10-02T11:50:00Z");
        rolledBack = executed(prod, "1.0.3", "2026-10-03T23:00:00Z", "2026-10-03T23:30:00Z",
                DeploymentOutcome.ROLLED_BACK, "2026-10-03T23:45:00Z", "{}");
        executed(staging, "1.0.4", "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z", null, null,
                "{}");
        executed(prod, "0.9.9", "2026-09-30T09:00:00Z", "2026-09-30T10:00:00Z",
                DeploymentOutcome.SUCCEEDED, "2026-09-30T10:05:00Z", "{}");

        request(prod, "F", QueryStatus.APPROVED, "2026-10-05T09:00:00Z");
        var g = request(prod, "G", QueryStatus.APPROVED, "2026-10-05T10:00:00Z");
        g.setSubmissionReason(SubmissionReason.EMERGENCY_ACCESS);
        requestRepository.saveAndFlush(g);
        reviewed(g, "2026-10-05T10:00:00Z", "2026-10-05T10:01:00Z");
        var h = request(prod, "H", QueryStatus.TIMED_OUT, "2026-10-06T09:00:00Z");
        h.setReviewStartedAt(Instant.parse("2026-10-06T09:01:00Z"));
        requestRepository.saveAndFlush(h);
        request(prod, "I", QueryStatus.TIMED_OUT, "2026-10-06T10:00:00Z");
        var j = request(prod, "J", QueryStatus.REJECTED, "2026-10-06T11:00:00Z");
        decision(j, "2026-10-06T11:30:00Z");
    }

    @Test
    void organizationMetricsMatchTheHandComputedFixture() {
        var view = metricsService.organizationMetrics(org.getId(),
                new DeploymentMetricsFilter(FROM, TO, null, null));

        assertThat(view.period().from()).isEqualTo(FROM);
        // A–E executed in the window; the EXECUTED → FAILED flip (B) still counts.
        assertThat(view.deploymentFrequency().total()).isEqualTo(5);
        assertThat(view.deploymentFrequency().perDay()).isCloseTo(5 / 7d, within(1e-9));
        // Samples 1800, 1800, 1800, 3600, 7200 → p50 1800, p95 3600 + 0.8 × 3600.
        assertThat(view.leadTime().sampleSize()).isEqualTo(5);
        assertThat(view.leadTime().p50Seconds()).isEqualTo(1800L);
        assertThat(view.leadTime().p95Seconds()).isEqualTo(6480L);
        assertThat(view.leadTime().source()).isEqualTo(LeadTimeSource.MIXED);
        assertThat(view.changeFailureRate().executed()).isEqualTo(5);
        assertThat(view.changeFailureRate().failed()).isEqualTo(1);
        assertThat(view.changeFailureRate().rolledBack()).isEqualTo(1);
        assertThat(view.changeFailureRate().unreported()).isEqualTo(1);
        assertThat(view.changeFailureRate().rate()).isCloseTo(0.4, within(1e-9));
        // B restored by C one hour after the failure was reported; D never restored.
        assertThat(view.timeToRestore().restored()).isEqualTo(1);
        assertThat(view.timeToRestore().openIncidents()).isEqualTo(1);
        assertThat(view.timeToRestore().p50Seconds()).isEqualTo(3600L);
        assertThat(view.timeToRestore().p95Seconds()).isEqualTo(3600L);
        // A (600 s) and C (900 s, last of two decisions); F, G and the legacy rows are absent.
        assertThat(view.approvalLatency().sampleSize()).isEqualTo(2);
        assertThat(view.approvalLatency().p50Seconds()).isEqualTo(750L);
        assertThat(view.approvalLatency().p95Seconds()).isEqualTo(885L);
        // Entered review: A, C, G, H, I (legacy TIMED_OUT), J (legacy decision) — H and I timed out.
        assertThat(view.timeoutRate().enteredReview()).isEqualTo(6);
        assertThat(view.timeoutRate().timedOut()).isEqualTo(2);
        assertThat(view.timeoutRate().rate()).isCloseTo(1 / 3d, within(1e-9));
        assertThat(view.breakGlassCount()).isEqualTo(1);
        assertThat(view.daily()).hasSize(7);
        assertThat(view.daily()).extracting(DeploymentMetricsView.DailyBucket::deployments)
                .containsExactly(1L, 2L, 1L, 1L, 0L, 0L, 0L);
        assertThat(view.daily().get(1).failed()).isEqualTo(1);
        assertThat(view.daily().get(2).rolledBack()).isEqualTo(1);
    }

    @Test
    void pipelineMetricsFilterByEnvironmentNameCaseInsensitively() {
        var view = metricsService.pipelineMetrics(pipeline.getId(), org.getId(), reviewer.getId(),
                Set.of(Permission.DEPLOYMENT_REVIEW),
                new DeploymentMetricsFilter(FROM, TO, "PROD", null));

        assertThat(view.deploymentFrequency().total()).isEqualTo(4);
        assertThat(view.changeFailureRate().unreported()).isZero();

        var unknown = metricsService.pipelineMetrics(pipeline.getId(), org.getId(),
                reviewer.getId(), Set.of(Permission.DEPLOYMENT_REVIEW),
                new DeploymentMetricsFilter(FROM, TO, "nope", null));
        assertThat(unknown.deploymentFrequency().total()).isZero();
        assertThat(unknown.daily()).hasSize(7);
    }

    @Test
    void dailyBucketsAreUtcWhateverTheSessionTimeZone() {
        var view = transactionTemplate.execute(status -> {
            entityManager.createNativeQuery("SET LOCAL TIME ZONE 'Pacific/Auckland'")
                    .executeUpdate();
            // Sanity: in this session a bare date_trunc moves D's 23:30 UTC onto Oct 4.
            var naive = entityManager.createNativeQuery("""
                    SELECT to_char(date_trunc('day', executed_at), 'YYYY-MM-DD')
                    FROM deployment_requests WHERE id = :id
                    """).setParameter("id", rolledBack.getId()).getSingleResult();
            assertThat(naive).isEqualTo("2026-10-04");
            return metricsService.organizationMetrics(org.getId(),
                    new DeploymentMetricsFilter(FROM, TO, null, null));
        });

        var october3 = view.daily().stream()
                .filter(day -> day.date().equals(LocalDate.parse("2026-10-03"))).findFirst()
                .orElseThrow();
        assertThat(october3.deployments()).isEqualTo(1);
        assertThat(october3.rolledBack()).isEqualTo(1);
        var october4 = view.daily().get(3);
        assertThat(october4.date()).isEqualTo(LocalDate.parse("2026-10-04"));
        assertThat(october4.deployments()).isEqualTo(1);
        assertThat(october4.rolledBack()).isZero();
    }

    @Test
    void aRequestDecidedThroughTheRealStateServiceIsAnApprovalLatencySample() {
        var own = pipeline();
        var env = environment(own, "prod", 0);
        var pending = request(env, "K", QueryStatus.PENDING_AI, "2026-10-07T09:00:00Z");

        transactionTemplate.executeWithoutResult(status ->
                stateService.apply(stateService.require(pending.getId()),
                        QueryStatus.PENDING_REVIEW));
        var reviewStartedAt = requestRepository.findById(pending.getId()).orElseThrow()
                .getReviewStartedAt();
        assertThat(reviewStartedAt).isNotNull();
        decision(pending, reviewStartedAt.plusSeconds(300).toString());
        transactionTemplate.executeWithoutResult(status ->
                stateService.apply(stateService.require(pending.getId()), QueryStatus.APPROVED));

        var view = metricsService.pipelineMetrics(own.getId(), org.getId(), reviewer.getId(),
                Set.of(Permission.DEPLOYMENT_REVIEW), new DeploymentMetricsFilter(
                        reviewStartedAt.minusSeconds(3600), reviewStartedAt.plusSeconds(3600),
                        null, null));
        assertThat(view.approvalLatency().sampleSize()).isEqualTo(1);
        assertThat(view.approvalLatency().p50Seconds()).isEqualTo(300L);
    }

    /**
     * Second pipeline, same window:
     * <pre>
     *   X1 exec 10-01 10:00 FAILED      reported 10:10, commit "2026-02-30T00:00:00Z" (invalid → created_at)
     *   X2 exec 10-01 11:00 FAILED      reported 11:10, commit 11:30Z — after execution, clamps to 0
     *   X3 exec 10-01 12:00 SUCCEEDED   commit without an offset (→ created_at); restores X1 and X2
     *   Y1 exec 10-02 10:00 ROLLED_BACK reported 13:00, commit "infinity" (→ created_at)
     *   Y2 exec 10-02 12:00 SUCCEEDED   commit "now" (→ created_at); restores Y1 before it was reported
     *   P  PENDING_REVIEW with one partial approval · Q TIMED_OUT after one partial approval
     * </pre>
     */
    @Test
    void restoreChainsPoisonedCommitTimestampsAndPartialApprovals() {
        var own = pipeline();
        var env = environment(own, "prod", 0);
        executed(env, "x1", "2026-10-01T09:30:00Z", "2026-10-01T10:00:00Z",
                DeploymentOutcome.FAILED, "2026-10-01T10:10:00Z",
                "{\"commit_timestamp\": \"2026-02-30T00:00:00Z\"}");
        executed(env, "x2", "2026-10-01T10:30:00Z", "2026-10-01T11:00:00Z",
                DeploymentOutcome.FAILED, "2026-10-01T11:10:00Z",
                "{\"commit_timestamp\": \"2026-10-01T11:30:00Z\"}");
        executed(env, "x3", "2026-10-01T11:30:00Z", "2026-10-01T12:00:00Z",
                DeploymentOutcome.SUCCEEDED, "2026-10-01T12:05:00Z",
                "{\"commit_timestamp\": \"2026-10-01T11:00:00\"}");
        executed(env, "y1", "2026-10-02T09:30:00Z", "2026-10-02T10:00:00Z",
                DeploymentOutcome.ROLLED_BACK, "2026-10-02T13:00:00Z",
                "{\"commit_timestamp\": \"infinity\"}");
        executed(env, "y2", "2026-10-02T11:30:00Z", "2026-10-02T12:00:00Z",
                DeploymentOutcome.SUCCEEDED, "2026-10-02T12:05:00Z",
                "{\"commit_timestamp\": \"now\"}");
        var p = request(env, "p", QueryStatus.PENDING_REVIEW, "2026-10-03T09:00:00Z");
        reviewed(p, "2026-10-03T09:01:00Z", "2026-10-03T09:10:00Z");
        var q = request(env, "q", QueryStatus.TIMED_OUT, "2026-10-03T10:00:00Z");
        reviewed(q, "2026-10-03T10:01:00Z", "2026-10-03T10:10:00Z");

        var view = metricsService.pipelineMetrics(own.getId(), org.getId(), reviewer.getId(),
                Set.of(Permission.DEPLOYMENT_REVIEW),
                new DeploymentMetricsFilter(FROM, TO, null, null));

        // Samples 1800, 0, 1800, 1800, 1800 — only X2's commit timestamp was accepted.
        assertThat(view.leadTime().sampleSize()).isEqualTo(5);
        assertThat(view.leadTime().p50Seconds()).isEqualTo(1800L);
        assertThat(view.leadTime().p95Seconds()).isEqualTo(1800L);
        assertThat(view.leadTime().source()).isEqualTo(LeadTimeSource.MIXED);
        // X1 → X3 6600 s, X2 → X3 3000 s, Y1 → Y2 clamped to 0: samples 0, 3000, 6600.
        assertThat(view.timeToRestore().restored()).isEqualTo(3);
        assertThat(view.timeToRestore().openIncidents()).isZero();
        assertThat(view.timeToRestore().p50Seconds()).isEqualTo(3000L);
        assertThat(view.timeToRestore().p95Seconds()).isEqualTo(6240L);
        // Neither partially approved request is a human decision.
        assertThat(view.approvalLatency().sampleSize()).isZero();
        assertThat(view.approvalLatency().p50Seconds()).isNull();
        assertThat(view.timeoutRate().enteredReview()).isEqualTo(2);
        assertThat(view.timeoutRate().timedOut()).isEqualTo(1);
    }

    @Test
    void permissionMatrixOverTheWebLayer() {
        var analystKey = apiKeyService.issue(submitter.getId(), org.getId(),
                "ci-" + UUID.randomUUID(), null).rawKey();
        var reviewerKey = apiKeyService.issue(reviewer.getId(), org.getId(),
                "rev-" + UUID.randomUUID(), null).rawKey();
        var window = "from=2026-10-01T00:00:00Z&to=2026-10-08T00:00:00Z";

        var noGrant = mvc.get()
                .uri("/api/v1/deployment-pipelines/" + pipeline.getId() + "/metrics?" + window)
                .header(API_KEY_HEADER, analystKey).exchange();
        assertThat(noGrant).hasStatus(404);
        assertThat(noGrant).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DEPLOYMENT_PIPELINE_NOT_FOUND");

        var orgWideDenied = mvc.get().uri("/api/v1/deployment-metrics?" + window)
                .header(API_KEY_HEADER, analystKey).exchange();
        assertThat(orgWideDenied).hasStatus(403);

        var orgWide = mvc.get()
                .uri("/api/v1/deployment-metrics?" + window + "&pipeline_id=" + pipeline.getId())
                .header(API_KEY_HEADER, reviewerKey).exchange();
        assertThat(orgWide).hasStatus(200);
        assertThat(orgWide).bodyJson().extractingPath("$.deployment_frequency.total")
                .isEqualTo(5);
        assertThat(orgWide).bodyJson().extractingPath("$.lead_time.source").asString()
                .isEqualTo("MIXED");
        assertThat(orgWide).bodyJson().extractingPath("$.daily[2].date").asString()
                .isEqualTo("2026-10-03");

        var inverted = mvc.get()
                .uri("/api/v1/deployment-pipelines/" + pipeline.getId()
                        + "/metrics?from=2026-10-08T00:00:00Z&to=2026-10-01T00:00:00Z")
                .header(API_KEY_HEADER, reviewerKey).exchange();
        assertThat(inverted).hasStatus(400);
        assertThat(inverted).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DEPLOYMENT_METRICS_PERIOD_INVALID");
    }

    // ---------------------------------------------------------------- fixtures

    private OrganizationEntity organization() {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("Acme-" + UUID.randomUUID());
        entity.setSlug("acme-" + UUID.randomUUID());
        return organizationRepository.save(entity);
    }

    private UserEntity user(UserRoleType role) {
        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setEmail(UUID.randomUUID() + "@example.com");
        entity.setDisplayName("User");
        entity.setPasswordHash("x");
        entity.setRole(role);
        entity.setAuthProvider(AuthProviderType.LOCAL);
        entity.setActive(true);
        entity.setOrganization(org);
        return userRepository.save(entity);
    }

    private DeploymentPipelineEntity pipeline() {
        var entity = new DeploymentPipelineEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setName("payments-api-" + UUID.randomUUID());
        entity.setProvider(PipelineProvider.GENERIC);
        return pipelineRepository.save(entity);
    }

    private DeploymentEnvironmentEntity environment(String name, int sortOrder) {
        return environment(pipeline, name, sortOrder);
    }

    private DeploymentEnvironmentEntity environment(DeploymentPipelineEntity owner, String name,
                                                    int sortOrder) {
        var entity = new DeploymentEnvironmentEntity();
        entity.setId(UUID.randomUUID());
        entity.setPipelineId(owner.getId());
        entity.setName(name);
        entity.setSortOrder(sortOrder);
        entity.setTags(new String[0]);
        return environmentRepository.save(entity);
    }

    private DeploymentRequestEntity request(DeploymentEnvironmentEntity environment,
                                            String version, QueryStatus status,
                                            String createdAt) {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setPipelineId(environment.getPipelineId());
        entity.setEnvironmentId(environment.getId());
        entity.setSubmittedBy(submitter.getId());
        entity.setVersion(version);
        entity.setStatus(status);
        entity.setCreatedAt(Instant.parse(createdAt));
        return requestRepository.saveAndFlush(entity);
    }

    private DeploymentRequestEntity executed(DeploymentEnvironmentEntity environment,
                                             String version, String createdAt, String executedAt,
                                             DeploymentOutcome outcome, String reportedAt,
                                             String metadata) {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setPipelineId(environment.getPipelineId());
        entity.setEnvironmentId(environment.getId());
        entity.setSubmittedBy(submitter.getId());
        entity.setVersion(version);
        entity.setMetadata(metadata);
        entity.setStatus(outcome == DeploymentOutcome.FAILED ? QueryStatus.FAILED
                : QueryStatus.EXECUTED);
        entity.setOutcome(outcome);
        entity.setOutcomeReportedAt(reportedAt == null ? null : Instant.parse(reportedAt));
        entity.setExecutedAt(Instant.parse(executedAt));
        entity.setCreatedAt(Instant.parse(createdAt));
        return requestRepository.saveAndFlush(entity);
    }

    private void reviewed(DeploymentRequestEntity request, String reviewStartedAt,
                          String... decidedAt) {
        var entity = requestRepository.findById(request.getId()).orElseThrow();
        entity.setReviewStartedAt(Instant.parse(reviewStartedAt));
        requestRepository.saveAndFlush(entity);
        for (var instant : decidedAt) {
            decision(request, instant);
        }
    }

    private void decision(DeploymentRequestEntity request, String decidedAt) {
        var entity = new DeploymentReviewDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setDeploymentRequestId(request.getId());
        // A fresh reviewer per decision: (request, reviewer, stage) is unique.
        entity.setReviewerId(user(UserRoleType.REVIEWER).getId());
        entity.setDecision(DecisionType.APPROVED);
        entity.setDecidedAt(Instant.parse(decidedAt));
        decisionRepository.saveAndFlush(entity);
    }
}
