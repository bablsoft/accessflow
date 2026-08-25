package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentReviewDecisionEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentReviewDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDeploymentNotificationLookupServiceTest {

    @Mock private DeploymentRequestRepository requestRepository;
    @Mock private DeploymentPipelineRepository pipelineRepository;
    @Mock private DeploymentEnvironmentRepository environmentRepository;
    @Mock private DeploymentReviewDecisionRepository decisionRepository;
    @Mock private ReviewPlanLookupService reviewPlanLookupService;
    @Mock private AiAnalysisLookupService aiAnalysisLookupService;
    @Mock private UserQueryService userQueryService;

    private DefaultDeploymentNotificationLookupService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultDeploymentNotificationLookupService(requestRepository,
                pipelineRepository, environmentRepository, decisionRepository,
                reviewPlanLookupService, aiAnalysisLookupService, userQueryService);
    }

    @Test
    void emptyWhenRequestMissing() {
        var id = UUID.randomUUID();
        when(requestRepository.findById(id)).thenReturn(Optional.empty());
        assertThat(service.find(id)).isEmpty();
    }

    @Test
    void mapsPipelineEnvironmentAndSubmitter() {
        var request = request();
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline(null)));
        when(environmentRepository.findById(environmentId))
                .thenReturn(Optional.of(environment(null)));

        var view = service.find(requestId).orElseThrow();

        assertThat(view.id()).isEqualTo(requestId);
        assertThat(view.organizationId()).isEqualTo(orgId);
        assertThat(view.pipelineId()).isEqualTo(pipelineId);
        assertThat(view.pipelineName()).isEqualTo("payments");
        assertThat(view.environmentName()).isEqualTo("production");
        assertThat(view.version()).isEqualTo("2.4.1");
        assertThat(view.submittedByUserId()).isEqualTo(submitterId);
        assertThat(view.status()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(view.aiRiskLevel()).isNull();
    }

    @Test
    void survivesDeletedPipelineAndEnvironment() {
        var request = request();
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.empty());
        when(environmentRepository.findById(environmentId)).thenReturn(Optional.empty());

        var view = service.find(requestId).orElseThrow();

        assertThat(view.pipelineName()).isNull();
        assertThat(view.environmentName()).isNull();
    }

    @Test
    void eligibleReviewersFallBackToReviewerAndAdminRolesWithoutPlanRules() {
        var reviewer = UUID.randomUUID();
        var admin = UUID.randomUUID();
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request()));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline(null)));
        when(environmentRepository.findById(environmentId))
                .thenReturn(Optional.of(environment(null)));
        when(userQueryService.findByOrganizationAndRole(orgId, UserRoleType.REVIEWER))
                .thenReturn(List.of(user(reviewer, UserRoleType.REVIEWER)));
        when(userQueryService.findByOrganizationAndRole(orgId, UserRoleType.ADMIN))
                .thenReturn(List.of(user(admin, UserRoleType.ADMIN)));

        assertThat(service.findEligibleReviewerUserIds(requestId))
                .containsExactly(reviewer, admin);
    }

    @Test
    void eligibleReviewersUsePlanApproverRulesWhenPresent() {
        var namedApprover = UUID.randomUUID();
        var roleHolder = UUID.randomUUID();
        var planId = UUID.randomUUID();
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request()));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline(planId)));
        when(environmentRepository.findById(environmentId))
                .thenReturn(Optional.of(environment(null)));
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(plan(List.of(
                new ApproverRule(namedApprover, null, 1),
                new ApproverRule(null, "SecOps", 1),
                new ApproverRule(UUID.randomUUID(), null, 2)))));
        when(userQueryService.findByOrganizationAndRoleName(orgId, "SecOps"))
                .thenReturn(List.of(user(roleHolder, UserRoleType.REVIEWER)));

        assertThat(service.findEligibleReviewerUserIds(requestId))
                .containsExactly(namedApprover, roleHolder);
    }

    @Test
    void environmentPlanOverrideWinsOverThePipelinePlan() {
        var envPlanId = UUID.randomUUID();
        var envApprover = UUID.randomUUID();
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request()));
        when(pipelineRepository.findById(pipelineId))
                .thenReturn(Optional.of(pipeline(UUID.randomUUID())));
        when(environmentRepository.findById(environmentId))
                .thenReturn(Optional.of(environment(envPlanId)));
        when(reviewPlanLookupService.findById(envPlanId)).thenReturn(Optional.of(plan(
                List.of(new ApproverRule(envApprover, null, 1)))));

        assertThat(service.findEligibleReviewerUserIds(requestId)).containsExactly(envApprover);
    }

    @Test
    void eligibleReviewersAlwaysExcludeTheSubmitter() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(request()));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline(null)));
        when(environmentRepository.findById(environmentId))
                .thenReturn(Optional.of(environment(null)));
        when(userQueryService.findByOrganizationAndRole(orgId, UserRoleType.REVIEWER))
                .thenReturn(List.of(user(submitterId, UserRoleType.REVIEWER)));
        when(userQueryService.findByOrganizationAndRole(orgId, UserRoleType.ADMIN))
                .thenReturn(List.of());

        assertThat(service.findEligibleReviewerUserIds(requestId)).isEmpty();
    }

    @Test
    void eligibleReviewersEmptyForUnknownRequest() {
        var id = UUID.randomUUID();
        when(requestRepository.findById(id)).thenReturn(Optional.empty());
        assertThat(service.findEligibleReviewerUserIds(id)).isEmpty();
    }

    @Test
    void approverIdsAreTheApprovedDecisionsOnly() {
        var approverA = UUID.randomUUID();
        var approverB = UUID.randomUUID();
        var rejecter = UUID.randomUUID();
        when(decisionRepository.findByDeploymentRequestIdOrderByStageAscDecidedAtAsc(requestId))
                .thenReturn(List.of(
                        decision(approverA, DecisionType.APPROVED),
                        decision(rejecter, DecisionType.REJECTED),
                        decision(approverB, DecisionType.APPROVED),
                        decision(approverA, DecisionType.APPROVED)));

        assertThat(service.findApproverUserIds(requestId)).containsExactly(approverA, approverB);
    }

    private DeploymentRequestEntity request() {
        var entity = new DeploymentRequestEntity();
        entity.setId(requestId);
        entity.setPipelineId(pipelineId);
        entity.setEnvironmentId(environmentId);
        entity.setOrganizationId(orgId);
        entity.setSubmittedBy(submitterId);
        entity.setVersion("2.4.1");
        entity.setStatus(QueryStatus.PENDING_REVIEW);
        return entity;
    }

    private DeploymentPipelineEntity pipeline(UUID reviewPlanId) {
        var entity = new DeploymentPipelineEntity();
        entity.setId(pipelineId);
        entity.setOrganizationId(orgId);
        entity.setName("payments");
        entity.setReviewPlanId(reviewPlanId);
        return entity;
    }

    private DeploymentEnvironmentEntity environment(UUID reviewPlanId) {
        var entity = new DeploymentEnvironmentEntity();
        entity.setId(environmentId);
        entity.setPipelineId(pipelineId);
        entity.setName("production");
        entity.setReviewPlanId(reviewPlanId);
        return entity;
    }

    private DeploymentReviewDecisionEntity decision(UUID reviewerId, DecisionType decisionType) {
        var entity = new DeploymentReviewDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setDeploymentRequestId(requestId);
        entity.setReviewerId(reviewerId);
        entity.setDecision(decisionType);
        entity.setStage(1);
        return entity;
    }

    private ReviewPlanSnapshot plan(List<ApproverRule> approvers) {
        return new ReviewPlanSnapshot(UUID.randomUUID(), orgId, true, true, 1, false,
                1, approvers, List.of(), null, null);
    }

    private UserView user(UUID id, UserRoleType role) {
        return new UserView(id, id + "@example.com", "User", role, orgId, true,
                AuthProviderType.LOCAL, "h", null, null, false, Instant.now());
    }
}
