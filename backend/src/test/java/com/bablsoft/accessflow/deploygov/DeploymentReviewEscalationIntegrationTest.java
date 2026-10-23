package com.bablsoft.accessflow.deploygov;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.AuthProviderType;
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
import com.bablsoft.accessflow.deploygov.api.DeploymentNotificationLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService.ReviewerContext;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.scheduled.DeploymentReviewEscalationJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@code DeploymentReviewEscalationJob} (#985) against real PostgreSQL: the native scans with
 * the environment-over-pipeline plan resolution, the stamps, the system audit rows, and the
 * notify-only guarantee — who may decide is identical before and after escalation.
 *
 * <p>No {@code deleteAll} cleanup: every identifier is randomized, so rows left in the shared
 * container never collide with other classes.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DeploymentReviewEscalationIntegrationTest {

    @Autowired DeploymentReviewEscalationJob job;
    @Autowired DeploymentPipelineAdminService pipelineService;
    @Autowired DeploymentReviewService reviewService;
    @Autowired DeploymentNotificationLookupService notificationLookupService;
    @Autowired DeploymentRequestRepository requestRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void escalatesAndNudgesAnIdleReviewOnceWithoutChangingEligibility() {
        var org = saveOrg();
        var submitter = saveUser(org, UserRoleType.ANALYST);
        var reviewer = saveUser(org, UserRoleType.REVIEWER);
        var plan = savePlan(org);
        var pipeline = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "escalating-" + UUID.randomUUID(), PipelineProvider.GITHUB_ACTIONS, null, null,
                plan.getId(), false, null));
        var environment = pipelineService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("production", 1, true, 1, null, false,
                        null, null, null));
        var idle = savePendingReview(pipeline.id(), environment.id(), org, submitter);
        var fresh = savePendingReview(pipeline.id(), environment.id(), org, submitter);
        backdate(idle, Instant.now().minus(2, ChronoUnit.HOURS));

        var reviewerContext = new ReviewerContext(reviewer.getId(), org.getId(), "REVIEWER",
                Set.of(Permission.DEPLOYMENT_REVIEW));
        var eligibleBefore = notificationLookupService.findEligibleReviewerUserIds(idle.getId());
        assertThat(reviewService.canReview(idle.getId(), reviewerContext)).isTrue();

        job.run();

        var escalated = requestRepository.findById(idle.getId()).orElseThrow();
        assertThat(escalated.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(escalated.getEscalatedAt()).isNotNull();
        assertThat(escalated.getLastNudgedAt()).isNotNull();
        var untouched = requestRepository.findById(fresh.getId()).orElseThrow();
        assertThat(untouched.getEscalatedAt()).isNull();
        assertThat(untouched.getLastNudgedAt()).isNull();

        assertThat(auditTriggers(idle, AuditAction.DEPLOYMENT_REVIEW_ESCALATED))
                .containsExactly("escalation");
        assertThat(auditTriggers(idle, AuditAction.DEPLOYMENT_REVIEW_NUDGED))
                .containsExactly("nudge");
        assertThat(auditActors(idle)).containsOnlyNulls();

        // A second pass inside the nudge interval re-fires neither.
        job.run();
        var again = requestRepository.findById(idle.getId()).orElseThrow();
        assertThat(again.getEscalatedAt()).isEqualTo(escalated.getEscalatedAt());
        assertThat(again.getLastNudgedAt()).isEqualTo(escalated.getLastNudgedAt());
        assertThat(auditTriggers(idle, AuditAction.DEPLOYMENT_REVIEW_ESCALATED)).hasSize(1);
        assertThat(auditTriggers(idle, AuditAction.DEPLOYMENT_REVIEW_NUDGED)).hasSize(1);

        // Notify-only: the same people may decide, and the reviewer still can.
        assertThat(notificationLookupService.findEligibleReviewerUserIds(idle.getId()))
                .containsExactlyInAnyOrderElementsOf(eligibleBefore);
        assertThat(reviewService.canReview(idle.getId(), reviewerContext)).isTrue();
        reviewService.approve(idle.getId(), reviewerContext, "after escalation");
        assertThat(requestRepository.findById(idle.getId()).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.APPROVED);
    }

    @Test
    void scansIgnoreRequestsWhosePlanDisablesEscalation() {
        var org = saveOrg();
        var submitter = saveUser(org, UserRoleType.ANALYST);
        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(org);
        plan.setName("Plan-" + UUID.randomUUID());
        plan.setApprovalTimeoutHours(24);
        reviewPlanRepository.save(plan);
        var pipeline = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "quiet-" + UUID.randomUUID(), PipelineProvider.GITHUB_ACTIONS, null, null,
                plan.getId(), false, null));
        var environment = pipelineService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("production", 1, true, 1, null, false,
                        null, null, null));
        var idle = savePendingReview(pipeline.id(), environment.id(), org, submitter);
        backdate(idle, Instant.now().minus(5, ChronoUnit.HOURS));

        assertThat(requestRepository.findEscalationDueIds(Instant.now()))
                .doesNotContain(idle.getId());
        assertThat(requestRepository.findNudgeDueIds(Instant.now())).doesNotContain(idle.getId());
    }

    private List<String> auditTriggers(DeploymentRequestEntity request,
                                                 AuditAction action) {
        return jdbcTemplate.queryForList(
                "SELECT metadata->>'trigger' FROM audit_log WHERE action = ? AND resource_id = ?",
                String.class, action.name(), request.getId());
    }

    private List<String> auditActors(DeploymentRequestEntity request) {
        return jdbcTemplate.queryForList(
                "SELECT actor_id::text FROM audit_log WHERE resource_id = ? AND action IN (?, ?)",
                String.class, request.getId(), AuditAction.DEPLOYMENT_REVIEW_ESCALATED.name(),
                AuditAction.DEPLOYMENT_REVIEW_NUDGED.name());
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

    private ReviewPlanEntity savePlan(OrganizationEntity org) {
        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(org);
        plan.setName("Plan-" + UUID.randomUUID());
        plan.setApprovalTimeoutHours(24);
        plan.setEscalationAfterHours(1);
        plan.setNudgeIntervalHours(1);
        return reviewPlanRepository.save(plan);
    }

    private OrganizationEntity saveOrg() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Org-" + UUID.randomUUID());
        org.setSlug("org-" + UUID.randomUUID());
        return organizationRepository.save(org);
    }

    private UserEntity saveUser(OrganizationEntity org, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setDisplayName("User");
        user.setPasswordHash("x");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }
}
