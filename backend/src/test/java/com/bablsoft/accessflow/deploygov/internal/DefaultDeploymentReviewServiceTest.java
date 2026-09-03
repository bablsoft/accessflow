package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisSummaryView;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService.PendingDeploymentReviewFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService.ReviewerContext;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewerNotEligibleException;
import com.bablsoft.accessflow.deploygov.api.DeploymentSelfApprovalException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentRequestStateException;
import com.bablsoft.accessflow.deploygov.events.DeploymentDecidedEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDeploymentReviewServiceTest {

    @Mock private DeploymentRequestRepository requestRepository;
    @Mock private DeploymentReviewDecisionRepository decisionRepository;
    @Mock private DeploymentPipelineRepository pipelineRepository;
    @Mock private DeploymentEnvironmentRepository environmentRepository;
    @Mock private DeploymentRequestStateService stateService;
    @Mock private AiAnalysisLookupService aiAnalysisLookupService;
    @Mock private ReviewPlanLookupService reviewPlanLookupService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DefaultDeploymentReviewService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultDeploymentReviewService(requestRepository, decisionRepository,
                pipelineRepository, environmentRepository, stateService, aiAnalysisLookupService,
                reviewPlanLookupService, eventPublisher);
    }

    private DeploymentRequestEntity pending() {
        var e = new DeploymentRequestEntity();
        e.setId(requestId);
        e.setOrganizationId(orgId);
        e.setPipelineId(pipelineId);
        e.setEnvironmentId(environmentId);
        e.setSubmittedBy(submitterId);
        e.setStatus(QueryStatus.PENDING_REVIEW);
        e.setRequiredApprovals(1);
        e.setVersion("2.4.1");
        return e;
    }

    private ReviewerContext reviewer() {
        return new ReviewerContext(reviewerId, orgId, "REVIEWER",
                SystemRolePermissions.of(UserRoleType.REVIEWER));
    }

    private void givenNoExistingDecision() {
        when(decisionRepository.findByDeploymentRequestIdAndReviewerIdAndStage(requestId,
                reviewerId, 1)).thenReturn(Optional.empty());
    }

    private void givenNoExistingDecisionAndSavable() {
        when(decisionRepository.findByDeploymentRequestIdAndReviewerIdAndStage(requestId,
                reviewerId, 1)).thenReturn(Optional.empty());
        when(decisionRepository.save(any())).thenAnswer(i -> {
            var d = (DeploymentReviewDecisionEntity) i.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
    }

    /** Resolves the pipeline's plan (no environment override) to the given approver rules. */
    private void givenResolvedPlan(ApproverRule... rules) {
        var planId = UUID.randomUUID();
        var pipeline = new DeploymentPipelineEntity();
        pipeline.setId(pipelineId);
        pipeline.setReviewPlanId(planId);
        var environment = new DeploymentEnvironmentEntity();
        environment.setId(environmentId);
        environment.setPipelineId(pipelineId);
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(environmentRepository.findById(environmentId)).thenReturn(Optional.of(environment));
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(
                new ReviewPlanSnapshot(planId, orgId, true, true, 1, false, 1,
                        List.of(rules), List.of())));
    }

    @Test
    void approveBelowThresholdStaysPendingReview() {
        var request = pending();
        request.setRequiredApprovals(2);
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(request));
        givenNoExistingDecisionAndSavable();
        when(decisionRepository.countByDeploymentRequestIdAndStageAndDecision(requestId, 1,
                DecisionType.APPROVED)).thenReturn(1L);

        var outcome = service.approve(requestId, reviewer(), "first of two");

        assertThat(outcome.decision()).isEqualTo(DecisionType.APPROVED);
        assertThat(outcome.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(outcome.duplicate()).isFalse();
        verify(stateService, never()).apply(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void approveReachingThresholdTransitionsToApprovedAndPublishes() {
        var request = pending();
        request.setRequiredApprovals(2);
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(request));
        givenNoExistingDecisionAndSavable();
        when(decisionRepository.countByDeploymentRequestIdAndStageAndDecision(requestId, 1,
                DecisionType.APPROVED)).thenReturn(2L);

        var outcome = service.approve(requestId, reviewer(), "second of two");

        assertThat(outcome.resultingStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(outcome.duplicate()).isFalse();
        verify(stateService).apply(request, QueryStatus.APPROVED);
        verify(eventPublisher).publishEvent(
                new DeploymentDecidedEvent(requestId, QueryStatus.APPROVED, null));
    }

    @Test
    void approveIsIdempotentOnReplay() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        var existing = new DeploymentReviewDecisionEntity();
        existing.setId(UUID.randomUUID());
        existing.setDecision(DecisionType.APPROVED);
        when(decisionRepository.findByDeploymentRequestIdAndReviewerIdAndStage(requestId,
                reviewerId, 1)).thenReturn(Optional.of(existing));

        var outcome = service.approve(requestId, reviewer(), "again");

        assertThat(outcome.decisionId()).isEqualTo(existing.getId());
        assertThat(outcome.duplicate()).isTrue();
        assertThat(outcome.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        verify(decisionRepository, never()).save(any());
        verify(stateService, never()).apply(any(), any());
    }

    @Test
    void singleRejectIsImmediatelyTerminal() {
        var request = pending();
        request.setRequiredApprovals(2);
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(request));
        givenNoExistingDecisionAndSavable();

        var outcome = service.reject(requestId, reviewer(), "no");

        assertThat(outcome.decision()).isEqualTo(DecisionType.REJECTED);
        assertThat(outcome.resultingStatus()).isEqualTo(QueryStatus.REJECTED);
        verify(stateService).apply(request, QueryStatus.REJECTED);
        verify(eventPublisher).publishEvent(
                new DeploymentDecidedEvent(requestId, QueryStatus.REJECTED, null));
    }

    @Test
    void submitterCannotSelfApproveRegardlessOfRole() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        // An ADMIN holding every permission (REVIEW_OVERRIDE included) still cannot self-approve.
        var submitter = new ReviewerContext(submitterId, orgId, "ADMIN",
                SystemRolePermissions.of(UserRoleType.ADMIN));

        assertThatThrownBy(() -> service.approve(requestId, submitter, "x"))
                .isInstanceOf(DeploymentSelfApprovalException.class);
        verify(stateService, never()).apply(any(), any());
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void cannotReviewNonPendingRequest() {
        var executed = pending();
        executed.setStatus(QueryStatus.EXECUTED);
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(executed));

        assertThatThrownBy(() -> service.approve(requestId, reviewer(), "x"))
                .isInstanceOf(IllegalDeploymentRequestStateException.class);
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void aCallerWithoutTheReviewPermissionIsRejectedInTheServiceNotJustTheController() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        var analyst = new ReviewerContext(reviewerId, orgId, "ANALYST",
                SystemRolePermissions.of(UserRoleType.ANALYST));

        assertThatThrownBy(() -> service.approve(requestId, analyst, "x"))
                .isInstanceOf(DeploymentReviewerNotEligibleException.class);
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void reviewOverrideBypassesApproverRules() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        givenNoExistingDecisionAndSavable();
        when(decisionRepository.countByDeploymentRequestIdAndStageAndDecision(requestId, 1,
                DecisionType.APPROVED)).thenReturn(1L);
        var admin = new ReviewerContext(reviewerId, orgId, "ADMIN",
                SystemRolePermissions.of(UserRoleType.ADMIN));

        assertThat(service.approve(requestId, admin, "ok").resultingStatus())
                .isEqualTo(QueryStatus.APPROVED);
        // The plan is never resolved for an override holder.
        verify(pipelineRepository, never()).findById(any());
        verify(environmentRepository, never()).findById(any());
        verify(reviewPlanLookupService, never()).findById(any());
    }

    @Test
    void aPlanWhoseRulesMatchNeitherUserNorRoleGatesTheReviewer() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        // Routed to ADMIN at stage 1; the reviewer's own userId only appears at stage 2.
        givenResolvedPlan(new ApproverRule(null, "ADMIN", 1),
                new ApproverRule(reviewerId, null, 2));

        assertThatThrownBy(() -> service.approve(requestId, reviewer(), "ok"))
                .isInstanceOf(DeploymentReviewerNotEligibleException.class);
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void aRoleRuleMatchesTheReviewerRoleNameCaseInsensitively() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        givenResolvedPlan(new ApproverRule(null, "reviewer", 1));
        givenNoExistingDecisionAndSavable();
        when(decisionRepository.countByDeploymentRequestIdAndStageAndDecision(requestId, 1,
                DecisionType.APPROVED)).thenReturn(1L);

        assertThat(service.approve(requestId, reviewer(), "ok").resultingStatus())
                .isEqualTo(QueryStatus.APPROVED);
    }

    @Test
    void anEnvironmentWithNoResolvedPlanStaysOpenToAnyPermittedReviewer() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.empty());
        when(environmentRepository.findById(environmentId)).thenReturn(Optional.empty());
        givenNoExistingDecisionAndSavable();
        when(decisionRepository.countByDeploymentRequestIdAndStageAndDecision(requestId, 1,
                DecisionType.APPROVED)).thenReturn(1L);

        assertThat(service.approve(requestId, reviewer(), "ok").resultingStatus())
                .isEqualTo(QueryStatus.APPROVED);
    }

    @Test
    void aPlanWithNoApproverRulesStaysOpenToAnyPermittedReviewer() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        givenResolvedPlan();
        givenNoExistingDecisionAndSavable();
        when(decisionRepository.countByDeploymentRequestIdAndStageAndDecision(requestId, 1,
                DecisionType.APPROVED)).thenReturn(1L);

        assertThat(service.approve(requestId, reviewer(), "ok").resultingStatus())
                .isEqualTo(QueryStatus.APPROVED);
    }

    @Test
    void canReviewIsTrueForAnEligibleReviewerOnAPendingRequest() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        givenResolvedPlan(new ApproverRule(reviewerId, null, 1));
        givenNoExistingDecision();

        assertThat(service.canReview(requestId, reviewer())).isTrue();
    }

    /**
     * Quorum leaves the request PENDING_REVIEW after the first of two approvals, so status alone
     * does not retire the buttons. Offering them anyway would let a reject silently replay the
     * reviewer's own earlier approval (duplicate=true, no decision row, no audit row).
     */
    @Test
    void canReviewIsFalseOnceTheCallerAlreadyVotedAtThisStage() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        givenResolvedPlan(new ApproverRule(reviewerId, null, 1));
        var existing = new DeploymentReviewDecisionEntity();
        existing.setId(UUID.randomUUID());
        existing.setDecision(DecisionType.APPROVED);
        when(decisionRepository.findByDeploymentRequestIdAndReviewerIdAndStage(requestId,
                reviewerId, 1)).thenReturn(Optional.of(existing));

        assertThat(service.canReview(requestId, reviewer())).isFalse();
    }

    @Test
    void canReviewIsFalseForTheSubmitterEvenAsAnAdmin() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        var submitter = new ReviewerContext(submitterId, orgId, "ADMIN",
                SystemRolePermissions.of(UserRoleType.ADMIN));

        assertThat(service.canReview(requestId, submitter)).isFalse();
    }

    @Test
    void canReviewIsFalseOnceTheRequestLeftReview() {
        var approved = pending();
        approved.setStatus(QueryStatus.APPROVED);
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(approved));

        assertThat(service.canReview(requestId, reviewer())).isFalse();
    }

    @Test
    void canReviewIsFalseWithoutTheReviewPermissionAndNeverReadsTheRequest() {
        var analyst = new ReviewerContext(reviewerId, orgId, "ANALYST",
                SystemRolePermissions.of(UserRoleType.ANALYST));

        assertThat(service.canReview(requestId, analyst)).isFalse();
        // The submitter opening their own deployment is the common caller — answer them for free.
        verify(requestRepository, never()).findByIdAndOrganizationId(any(), any());
    }

    @Test
    void canReviewIsFalseWhenThePlanNamesOtherApprovers() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        givenResolvedPlan(new ApproverRule(null, "ADMIN", 1));

        assertThat(service.canReview(requestId, reviewer())).isFalse();
    }

    @Test
    void canReviewIsTrueUnderReviewOverrideWithoutResolvingThePlan() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        var admin = new ReviewerContext(reviewerId, orgId, "ADMIN",
                SystemRolePermissions.of(UserRoleType.ADMIN));

        givenNoExistingDecision();

        assertThat(service.canReview(requestId, admin)).isTrue();
        verify(reviewPlanLookupService, never()).findById(any());
    }

    @Test
    void canReviewIsTrueWhenThePlanNamesNoApprovers() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.of(pending()));
        givenResolvedPlan();

        givenNoExistingDecision();

        assertThat(service.canReview(requestId, reviewer())).isTrue();
    }

    @Test
    void canReviewAnswersFalseForAnUnknownOrCrossOrgIdRatherThanThrowing() {
        when(requestRepository.findByIdAndOrganizationId(requestId, orgId))
                .thenReturn(Optional.empty());

        assertThat(service.canReview(requestId, reviewer())).isFalse();
    }

    @Test
    void listPendingIsEmptyWithoutTheReviewPermission() {
        var analyst = new ReviewerContext(reviewerId, orgId, "ANALYST",
                SystemRolePermissions.of(UserRoleType.ANALYST));

        var page = service.listPending(analyst, new PendingDeploymentReviewFilter(null),
                PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
        verify(requestRepository, never()).findAll(
                org.mockito.ArgumentMatchers.<Specification<DeploymentRequestEntity>>any(),
                any(Pageable.class));
    }

    @Test
    void listPendingMapsTheSpecPageWithResolvedNamesAndAiSummary() {
        var analysisId = UUID.randomUUID();
        var request = pending();
        request.setAiAnalysisId(analysisId);
        request.setCommitSha("abc123");
        var pipeline = new DeploymentPipelineEntity();
        pipeline.setId(pipelineId);
        pipeline.setOrganizationId(orgId);
        pipeline.setName("api-deploy");
        var environment = new DeploymentEnvironmentEntity();
        environment.setId(environmentId);
        environment.setPipelineId(pipelineId);
        environment.setName("production");
        // Reach resolution: no plan anywhere, so the environment is reviewable by any holder.
        when(pipelineRepository.findAllByOrganizationId(orgId)).thenReturn(List.of(pipeline));
        when(environmentRepository.findByPipelineIdOrderBySortOrderAscNameAsc(pipelineId))
                .thenReturn(List.of(environment));
        when(requestRepository.findAll(
                org.mockito.ArgumentMatchers.<Specification<DeploymentRequestEntity>>any(),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(request)));
        when(pipelineRepository.findById(pipelineId)).thenReturn(Optional.of(pipeline));
        when(environmentRepository.findById(environmentId)).thenReturn(Optional.of(environment));
        when(aiAnalysisLookupService.findById(analysisId)).thenReturn(Optional.of(
                new AiAnalysisSummaryView(analysisId, null, RiskLevel.HIGH, 82,
                        "Risky deploy", false, null)));

        var page = service.listPending(reviewer(), new PendingDeploymentReviewFilter(null),
                PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        var view = page.content().get(0);
        assertThat(view.deploymentRequestId()).isEqualTo(requestId);
        assertThat(view.pipelineName()).isEqualTo("api-deploy");
        assertThat(view.environmentName()).isEqualTo("production");
        assertThat(view.submittedByUserId()).isEqualTo(submitterId);
        assertThat(view.version()).isEqualTo("2.4.1");
        assertThat(view.commitSha()).isEqualTo("abc123");
        assertThat(view.aiAnalysisId()).isEqualTo(analysisId);
        assertThat(view.aiRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(view.aiRiskScore()).isEqualTo(82);
        assertThat(view.aiSummary()).isEqualTo("Risky deploy");
        assertThat(view.requiredApprovals()).isEqualTo(1);
    }

    @Test
    void listPendingWithReviewOverrideSkipsReachResolution() {
        var admin = new ReviewerContext(reviewerId, orgId, "ADMIN",
                SystemRolePermissions.of(UserRoleType.ADMIN));
        when(requestRepository.findAll(
                org.mockito.ArgumentMatchers.<Specification<DeploymentRequestEntity>>any(),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        var page = service.listPending(admin, new PendingDeploymentReviewFilter(null),
                PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
        verify(pipelineRepository, never()).findAllByOrganizationId(any());
    }
}
