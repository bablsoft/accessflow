package com.bablsoft.accessflow.deploygov;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentEnvironmentCommand;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestService;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService.ReviewerContext;
import com.bablsoft.accessflow.deploygov.api.DeploymentSelfApprovalException;
import com.bablsoft.accessflow.deploygov.api.GrantDeploymentPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.api.SubmitDeploymentRequestCommand;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineUserPermissionRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentReviewDecisionRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The #692 review half end-to-end against Postgres: quorum counting on the request's folded
 * {@code required_approvals}, idempotent duplicate decisions, the self-approval ban, and the
 * native stale-{@code PENDING_REVIEW} timeout scan the {@code DeploymentTimeoutJob} runs.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeploymentReviewIntegrationTest {

    @Autowired DeploymentRequestService requestService;
    @Autowired DeploymentReviewService reviewService;
    @Autowired DeploymentPipelineAdminService pipelineService;
    @Autowired DeploymentPermissionService permissionService;
    @Autowired DeploymentRequestRepository requestRepository;
    @Autowired DeploymentReviewDecisionRepository decisionRepository;
    @Autowired DeploymentPipelineUserPermissionRepository userPermissionRepository;
    @Autowired DeploymentEnvironmentRepository environmentRepository;
    @Autowired DeploymentPipelineRepository pipelineRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        decisionRepository.deleteAll();
        requestRepository.deleteAll();
        userPermissionRepository.deleteAll();
        environmentRepository.deleteAll();
        pipelineRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void quorumOfTwoApprovalsPromotesAndDuplicatesAreIdempotent() {
        var fixture = fixture();
        var requestId = submitToPendingReview(fixture);
        var reviewerA = reviewerContext(fixture);
        var reviewerB = reviewerContext(fixture);

        var first = reviewService.approve(requestId, reviewerA, "lgtm");
        assertThat(first.duplicate()).isFalse();
        assertThat(first.decision()).isEqualTo(DecisionType.APPROVED);
        assertThat(first.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.PENDING_REVIEW);

        var duplicate = reviewService.approve(requestId, reviewerA, "again");
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.decisionId()).isEqualTo(first.decisionId());
        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.PENDING_REVIEW);

        var second = reviewService.approve(requestId, reviewerB, "ship it");
        assertThat(second.duplicate()).isFalse();
        assertThat(second.resultingStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.APPROVED);
        assertThat(decisionRepository
                .findByDeploymentRequestIdOrderByStageAscDecidedAtAsc(requestId)).hasSize(2);
    }

    @Test
    void canReviewAnswersTheSameQuestionTheDecisionGuardEnforces() {
        var fixture = fixture();
        var requestId = submitToPendingReview(fixture);
        var reviewer = reviewerContext(fixture);
        var submitterContext = new ReviewerContext(fixture.userId(), fixture.orgId(), "ANALYST",
                Set.of(Permission.DEPLOYMENT_REVIEW));

        assertThat(reviewService.canReview(requestId, reviewer)).isTrue();
        assertThat(reviewService.canReview(requestId, submitterContext)).isFalse();
        assertThat(reviewService.canReview(UUID.randomUUID(), reviewer)).isFalse();

        // The environment asks for two approvals, so it takes both to leave review.
        reviewService.approve(requestId, reviewer, "lgtm");
        reviewService.approve(requestId, reviewerContext(fixture), "ship it");

        // Once the request leaves review nobody may decide it again, the approvers included.
        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.APPROVED);
        assertThat(reviewService.canReview(requestId, reviewer)).isFalse();
    }

    @Test
    void theSubmitterCanNeverApproveTheirOwnDeployment() {
        var fixture = fixture();
        var requestId = submitToPendingReview(fixture);
        var submitterContext = new ReviewerContext(fixture.userId(), fixture.orgId(), "ANALYST",
                Set.of(Permission.DEPLOYMENT_REVIEW));

        assertThatThrownBy(() -> reviewService.approve(requestId, submitterContext, "self"))
                .isInstanceOf(DeploymentSelfApprovalException.class);

        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void staleScanReturnsOnlyPlanCoveredOverdueRequests() {
        var org = saveOrg();
        var user = saveUser(org);
        var plan = savePlan(org, 1);
        var plannedPipeline = pipelineService.create(new CreateDeploymentPipelineCommand(
                org.getId(), "planned-" + UUID.randomUUID(), PipelineProvider.GITHUB_ACTIONS, null,
                null, plan.getId(), false, null));
        var plannedEnvironment = pipelineService.createEnvironment(plannedPipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("production", 1, true, 1, null, false, null));
        var planlessPipeline = pipelineService.create(new CreateDeploymentPipelineCommand(
                org.getId(), "planless-" + UUID.randomUUID(), PipelineProvider.GITHUB_ACTIONS, null,
                null, null, false, null));
        var planlessEnvironment = pipelineService.createEnvironment(planlessPipeline.id(),
                org.getId(), new CreateDeploymentEnvironmentCommand("production", 1, true, 1, null,
                        false, null));

        var stale = savePendingReview(plannedPipeline.id(), plannedEnvironment.id(), org, user);
        var fresh = savePendingReview(plannedPipeline.id(), plannedEnvironment.id(), org, user);
        var planless = savePendingReview(planlessPipeline.id(), planlessEnvironment.id(), org, user);
        // created_at is stamped on insert; age the stale and plan-less rows past the 1h timeout.
        backdate(stale, Instant.now().minus(3, ChronoUnit.HOURS));
        backdate(planless, Instant.now().minus(3, ChronoUnit.HOURS));

        var ids = requestRepository.findStalePendingReviewIds(Instant.now());

        assertThat(ids).containsExactly(stale.getId());
        assertThat(ids).doesNotContain(fresh.getId(), planless.getId());
    }

    /** Submits through the service and waits for the async routing to land in PENDING_REVIEW. */
    private UUID submitToPendingReview(Fixture fixture) {
        var result = requestService.submit(new SubmitDeploymentRequestCommand(fixture.pipelineId(),
                "production", fixture.orgId(), fixture.userId(), false, "2.4.1", "abc123",
                "ghcr.io/app:2.4.1", "https://ci/run/1", "run-" + UUID.randomUUID(),
                Map.of("changelog", "fix things"), "ship it", null, null, "10.0.0.1"));
        var requestId = result.request().id();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                        .isEqualTo(QueryStatus.PENDING_REVIEW));
        assertThat(requestRepository.findById(requestId).orElseThrow().getRequiredApprovals())
                .isEqualTo(2);
        return requestId;
    }

    private ReviewerContext reviewerContext(Fixture fixture) {
        var reviewer = saveUser(organizationRepository.findById(fixture.orgId()).orElseThrow());
        return new ReviewerContext(reviewer.getId(), fixture.orgId(), "ANALYST",
                Set.of(Permission.DEPLOYMENT_REVIEW));
    }

    private DeploymentRequestEntity savePendingReview(UUID pipelineId, UUID environmentId,
                                                      OrganizationEntity org, UserEntity user) {
        var entity = new DeploymentRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setPipelineId(pipelineId);
        entity.setEnvironmentId(environmentId);
        entity.setOrganizationId(org.getId());
        entity.setSubmittedBy(user.getId());
        entity.setVersion("1.0.0");
        entity.setStatus(QueryStatus.PENDING_REVIEW);
        entity.setRequiredApprovals(1);
        return requestRepository.save(entity);
    }

    private void backdate(DeploymentRequestEntity entity, Instant createdAt) {
        jdbcTemplate.update("UPDATE deployment_requests SET created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), entity.getId());
    }

    private ReviewPlanEntity savePlan(OrganizationEntity org, int approvalTimeoutHours) {
        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(org);
        plan.setName("Plan-" + UUID.randomUUID());
        plan.setApprovalTimeoutHours(approvalTimeoutHours);
        return reviewPlanRepository.save(plan);
    }

    private record Fixture(UUID orgId, UUID userId, UUID pipelineId) {
    }

    private Fixture fixture() {
        var org = saveOrg();
        var user = saveUser(org);
        // AI analysis off, so the analysis listener skips and routing lands in PENDING_REVIEW; the
        // environment requires two approvals and resolves no review plan, so any DEPLOYMENT_REVIEW
        // holder is eligible.
        var pipeline = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "payments-" + UUID.randomUUID(), PipelineProvider.GITHUB_ACTIONS, null, null, null,
                false, null));
        pipelineService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("production", 1, true, 2, null, false, null));
        permissionService.grantPermission(pipeline.id(), org.getId(), user.getId(),
                new GrantDeploymentPermissionCommand(user.getId(), true, false, null));
        return new Fixture(org.getId(), user.getId(), pipeline.id());
    }

    private OrganizationEntity saveOrg() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Org-" + UUID.randomUUID());
        org.setSlug("org-" + UUID.randomUUID());
        return organizationRepository.save(org);
    }

    private UserEntity saveUser(OrganizationEntity org) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setDisplayName("Reviewer");
        user.setPasswordHash("x");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }
}
