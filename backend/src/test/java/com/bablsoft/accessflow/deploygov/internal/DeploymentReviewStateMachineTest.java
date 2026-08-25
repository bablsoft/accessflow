package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.events.DeploymentAnalysisCompletedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentAnalysisFailedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentAnalysisSkippedEvent;
import com.bablsoft.accessflow.deploygov.events.DeploymentDecidedEvent;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.routing.DeploymentRoutingPolicyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentReviewStateMachineTest {

    private static final UUID ORG = UUID.randomUUID();

    private DeploymentRequestRepository requestRepository;
    private DeploymentPipelineRepository pipelineRepository;
    private DeploymentEnvironmentRepository environmentRepository;
    private DeploymentRoutingPolicyEngine routingEngine;
    private ReviewPlanLookupService reviewPlanLookupService;
    private DeploymentRequestStateService stateService;
    private DeploygovAuditWriter auditWriter;
    private ApplicationEventPublisher eventPublisher;
    private DeploymentReviewStateMachine machine;

    private DeploymentPipelineEntity pipeline;
    private DeploymentEnvironmentEntity environment;

    @BeforeEach
    void setUp() {
        requestRepository = mock(DeploymentRequestRepository.class);
        pipelineRepository = mock(DeploymentPipelineRepository.class);
        environmentRepository = mock(DeploymentEnvironmentRepository.class);
        routingEngine = mock(DeploymentRoutingPolicyEngine.class);
        reviewPlanLookupService = mock(ReviewPlanLookupService.class);
        stateService = mock(DeploymentRequestStateService.class);
        auditWriter = mock(DeploygovAuditWriter.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        machine = new DeploymentReviewStateMachine(requestRepository, pipelineRepository,
                environmentRepository, routingEngine, reviewPlanLookupService, stateService,
                auditWriter,
                eventPublisher, Clock.fixed(Instant.parse("2026-08-21T17:30:00Z"), ZoneOffset.UTC));

        pipeline = pipeline();
        environment = environment(true, null, null);
        lenient().when(pipelineRepository.findById(pipeline.getId())).thenReturn(Optional.of(pipeline));
        lenient().when(environmentRepository.findById(environment.getId()))
                .thenReturn(Optional.of(environment));
        lenient().when(reviewPlanLookupService.findById(any())).thenReturn(Optional.empty());
        lenient().when(routingEngine.evaluate(any(), any(), any())).thenReturn(null);
    }

    @Test
    void requestNotInPendingAiIsIgnored() {
        var request = stubRequest(QueryStatus.APPROVED);

        machine.decide(request.getId(), RiskLevel.LOW);

        verify(stateService, never()).apply(any(), any());
    }

    @Test
    void unknownRequestIsIgnored() {
        var id = UUID.randomUUID();
        when(requestRepository.findById(id)).thenReturn(Optional.empty());

        machine.decide(id, RiskLevel.LOW);
        machine.forceReview(id);

        verify(stateService, never()).apply(any(), any());
    }

    @Test
    void missingPipelineFailsSafeToReview() {
        var request = stubRequest(QueryStatus.PENDING_AI);
        when(pipelineRepository.findById(pipeline.getId())).thenReturn(Optional.empty());

        machine.decide(request.getId(), RiskLevel.LOW);

        verify(stateService).apply(request, QueryStatus.PENDING_REVIEW);
        assertThat(request.getRequiredApprovals()).isEqualTo(1);
    }

    @Test
    void noRoutingMatchRoutesToReviewWhenTheEnvironmentRequiresIt() {
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.decide(request.getId(), RiskLevel.LOW);

        verify(stateService).apply(request, QueryStatus.PENDING_REVIEW);
    }

    @Test
    void noRoutingMatchApprovesWhenTheEnvironmentDoesNotRequireReview() {
        environment.setRequireReview(false);
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.decide(request.getId(), RiskLevel.LOW);

        verify(stateService).apply(request, QueryStatus.APPROVED);
        verify(eventPublisher).publishEvent(any(DeploymentDecidedEvent.class));
    }

    @Test
    void aPlanThatNeedsNoHumanApprovalRelaxesTheEnvironmentFlag() {
        var planId = UUID.randomUUID();
        environment.setReviewPlanId(planId);
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(plan(3, false)));
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.decide(request.getId(), RiskLevel.LOW);

        verify(stateService).apply(request, QueryStatus.APPROVED);
    }

    @Test
    void skippedAnalysisStillRoutesThroughTheEnvironmentPolicy() {
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.onSkipped(new DeploymentAnalysisSkippedEvent(request.getId(), "ai_disabled"));

        verify(routingEngine).evaluate(eq(ORG), eq(pipeline.getId()), any());
        verify(stateService).apply(request, QueryStatus.PENDING_REVIEW);
    }

    @Test
    void completedAnalysisPassesTheRiskLevelToTheEngine() {
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.onCompleted(new DeploymentAnalysisCompletedEvent(request.getId(), UUID.randomUUID(),
                RiskLevel.CRITICAL, 91, "risky"));

        var captor = ArgumentCaptor.forClass(DeploymentRoutingPolicyEngine.RoutingContext.class);
        verify(routingEngine).evaluate(eq(ORG), eq(pipeline.getId()), captor.capture());
        assertThat(captor.getValue().riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(captor.getValue().environmentName()).isEqualTo("production");
        assertThat(captor.getValue().version()).isEqualTo("2.4.1");
        assertThat(captor.getValue().provider()).isEqualTo(PipelineProvider.GITHUB_ACTIONS);
    }

    @Test
    void failedAnalysisForcesReviewWithoutConsultingRouting() {
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.onFailed(new DeploymentAnalysisFailedEvent(request.getId(), "provider down"));

        verify(routingEngine, never()).evaluate(any(), any(), any());
        verify(stateService).apply(request, QueryStatus.PENDING_REVIEW);
    }

    @Test
    void failedAnalysisUsesTheResolvedApprovalCount() {
        environment.setRequiredApprovals(4);
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.forceReview(request.getId());

        assertThat(request.getRequiredApprovals()).isEqualTo(4);
    }

    @Test
    void routingAutoApproveApprovesAndTagsTheEvent() {
        var policyId = UUID.randomUUID();
        stubMatch(policyId, DeploymentRoutingAction.AUTO_APPROVE, null);
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.decide(request.getId(), RiskLevel.LOW);

        verify(stateService).apply(request, QueryStatus.APPROVED);
        var captor = ArgumentCaptor.forClass(DeploymentDecidedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(QueryStatus.APPROVED);
        assertThat(captor.getValue().reason()).isEqualTo("routing:" + policyId);
        // #695: routing decisions audit as system rows (null actor) naming the policy.
        var metadataCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditWriter).record(
                org.mockito.ArgumentMatchers.eq(
                        com.bablsoft.accessflow.audit.api.AuditAction.DEPLOYMENT_APPROVED),
                org.mockito.ArgumentMatchers.eq(
                        com.bablsoft.accessflow.audit.api.AuditResourceType.DEPLOYMENT_REQUEST),
                org.mockito.ArgumentMatchers.eq(request.getId()), any(),
                org.mockito.ArgumentMatchers.isNull(), metadataCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(metadataCaptor.getValue())
                .containsEntry("trigger", "routing")
                .containsEntry("policy_id", policyId.toString());
    }

    @Test
    void routingAutoRejectRejectsAndTagsTheEvent() {
        var policyId = UUID.randomUUID();
        stubMatch(policyId, DeploymentRoutingAction.AUTO_REJECT, null);
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.decide(request.getId(), RiskLevel.HIGH);

        verify(stateService).apply(request, QueryStatus.REJECTED);
        var captor = ArgumentCaptor.forClass(DeploymentDecidedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo("routing:" + policyId);
        verify(auditWriter).record(
                org.mockito.ArgumentMatchers.eq(
                        com.bablsoft.accessflow.audit.api.AuditAction.DEPLOYMENT_REJECTED),
                org.mockito.ArgumentMatchers.eq(
                        com.bablsoft.accessflow.audit.api.AuditResourceType.DEPLOYMENT_REQUEST),
                org.mockito.ArgumentMatchers.eq(request.getId()), any(),
                org.mockito.ArgumentMatchers.isNull(), any(),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void routingRequireApprovalsReplacesTheResolvedCount() {
        environment.setRequiredApprovals(4);
        stubMatch(UUID.randomUUID(), DeploymentRoutingAction.REQUIRE_APPROVALS, 2);
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.decide(request.getId(), RiskLevel.HIGH);

        verify(stateService).apply(request, QueryStatus.PENDING_REVIEW);
        assertThat(request.getRequiredApprovals()).isEqualTo(2);
    }

    @Test
    void routingRequireApprovalsFallsBackToOne() {
        stubMatch(UUID.randomUUID(), DeploymentRoutingAction.REQUIRE_APPROVALS, null);
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.decide(request.getId(), RiskLevel.HIGH);

        assertThat(request.getRequiredApprovals()).isEqualTo(1);
    }

    @Test
    void routingEscalateAddsToTheResolvedCount() {
        environment.setRequiredApprovals(2);
        stubMatch(UUID.randomUUID(), DeploymentRoutingAction.ESCALATE, 3);
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.decide(request.getId(), RiskLevel.CRITICAL);

        verify(stateService).apply(request, QueryStatus.PENDING_REVIEW);
        assertThat(request.getRequiredApprovals()).isEqualTo(5);
    }

    @Test
    void routingEscalateAddsOneByDefaultOverThePlanCount() {
        var planId = UUID.randomUUID();
        pipeline.setReviewPlanId(planId);
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(plan(2, true)));
        stubMatch(UUID.randomUUID(), DeploymentRoutingAction.ESCALATE, null);
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.decide(request.getId(), RiskLevel.CRITICAL);

        assertThat(request.getRequiredApprovals()).isEqualTo(3);
    }

    @Test
    void theEnvironmentPlanOverridesThePipelinePlan() {
        var pipelinePlan = UUID.randomUUID();
        var environmentPlan = UUID.randomUUID();
        pipeline.setReviewPlanId(pipelinePlan);
        environment.setReviewPlanId(environmentPlan);
        when(reviewPlanLookupService.findById(environmentPlan)).thenReturn(Optional.of(plan(3, true)));
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.decide(request.getId(), RiskLevel.LOW);

        verify(reviewPlanLookupService).findById(environmentPlan);
        verify(reviewPlanLookupService, never()).findById(pipelinePlan);
        assertThat(request.getRequiredApprovals()).isEqualTo(3);
    }

    @Test
    void theEnvironmentApprovalOverrideBeatsThePlanCount() {
        var planId = UUID.randomUUID();
        pipeline.setReviewPlanId(planId);
        environment.setRequiredApprovals(5);
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(plan(2, true)));
        var request = stubRequest(QueryStatus.PENDING_AI);

        machine.decide(request.getId(), RiskLevel.LOW);

        assertThat(request.getRequiredApprovals()).isEqualTo(5);
    }

    private void stubMatch(UUID policyId, DeploymentRoutingAction action, Integer requiredApprovals) {
        when(routingEngine.evaluate(any(), any(), any())).thenReturn(
                new DeploymentRoutingPolicyEngine.RoutingMatch(policyId, action, requiredApprovals));
    }

    private DeploymentRequestEntity stubRequest(QueryStatus status) {
        var request = new DeploymentRequestEntity();
        request.setId(UUID.randomUUID());
        request.setOrganizationId(ORG);
        request.setPipelineId(pipeline.getId());
        request.setEnvironmentId(environment.getId());
        request.setSubmittedBy(UUID.randomUUID());
        request.setVersion("2.4.1");
        request.setStatus(status);
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        return request;
    }

    private static DeploymentPipelineEntity pipeline() {
        var entity = new DeploymentPipelineEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setName("payments-api");
        entity.setProvider(PipelineProvider.GITHUB_ACTIONS);
        entity.setActive(true);
        return entity;
    }

    private static DeploymentEnvironmentEntity environment(boolean requireReview,
                                                           Integer requiredApprovals, UUID planId) {
        var entity = new DeploymentEnvironmentEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("production");
        entity.setRequireReview(requireReview);
        entity.setRequiredApprovals(requiredApprovals);
        entity.setReviewPlanId(planId);
        return entity;
    }

    private static ReviewPlanSnapshot plan(int minApprovals, boolean requiresHumanApproval) {
        return new ReviewPlanSnapshot(UUID.randomUUID(), ORG, true, requiresHumanApproval,
                minApprovals, false, 1, List.of(), List.of());
    }
}
