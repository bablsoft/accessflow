package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
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
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Applies what {@link DeploymentDecisionEvaluator} decided for a deployment: the status transition,
 * the approval count, the system audit row and the event that fans the outcome out.
 *
 * <p>Since issue AF-967 this class only <em>applies</em>. Every rule about routing policies and the
 * environment's own review policy lives in the evaluator, so the decision explainer can replay it
 * without any of the side effects below.
 */
@Component
@RequiredArgsConstructor
class DeploymentReviewStateMachine {

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentEnvironmentRepository environmentRepository;
    private final DeploymentDecisionEvaluator decisionEvaluator;
    private final DeploymentRequestStateService stateService;
    private final DeploygovAuditWriter auditWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @ApplicationModuleListener
    void onCompleted(DeploymentAnalysisCompletedEvent event) {
        decide(event.deploymentRequestId(), AiOutcome.COMPLETED, event.riskLevel());
    }

    @ApplicationModuleListener
    void onSkipped(DeploymentAnalysisSkippedEvent event) {
        decide(event.deploymentRequestId(), AiOutcome.SKIPPED, null);
    }

    @ApplicationModuleListener
    void onFailed(DeploymentAnalysisFailedEvent event) {
        decide(event.deploymentRequestId(), AiOutcome.FAILED, null);
    }

    @Transactional
    void decide(UUID deploymentRequestId, AiOutcome aiOutcome, RiskLevel riskLevel) {
        var request = requestRepository.findById(deploymentRequestId).orElse(null);
        if (request == null || request.getStatus() != QueryStatus.PENDING_AI) {
            return;
        }
        var pipeline = pipelineRepository.findById(request.getPipelineId()).orElse(null);
        var environment = environmentRepository.findById(request.getEnvironmentId()).orElse(null);
        if (pipeline == null || environment == null) {
            // A pipeline or environment deleted between the trigger and the analysis leaves no
            // policy to consult, so the release falls back to human review rather than being
            // decided by default. The environment's own approval override still applies when the
            // environment is the half that survived: losing the pipeline row must not quietly drop
            // a production release from four approvals to one.
            routeToReview(request, environment != null && environment.getRequiredApprovals() != null
                    ? environment.getRequiredApprovals() : 1);
            return;
        }
        apply(request, environment,
                decisionEvaluator.evaluate(toInput(request, pipeline, environment, aiOutcome,
                        riskLevel)));
    }

    private void apply(DeploymentRequestEntity request, DeploymentEnvironmentEntity environment,
                       DeploymentDecision decision) {
        switch (decision.kind()) {
            case ROUTING_AUTO_APPROVE -> approve(request, decision.routingMatch().policyId());
            case ROUTING_AUTO_REJECT -> {
                stateService.apply(request, QueryStatus.REJECTED);
                auditDecision(request, AuditAction.DEPLOYMENT_REJECTED,
                        decision.routingMatch().policyId());
                eventPublisher.publishEvent(new DeploymentDecidedEvent(request.getId(),
                        QueryStatus.REJECTED, "routing:" + decision.routingMatch().policyId()));
            }
            case ROUTING_REQUIRE_APPROVALS, ROUTING_ESCALATE, ENVIRONMENT_PENDING_REVIEW,
                 AI_FAILED_PENDING_REVIEW ->
                    routeToReview(request, decision.effectiveApprovals() != null
                            ? decision.effectiveApprovals() : 1);
            case ENVIRONMENT_APPROVED -> approve(request, null);
            // A switch statement is not exhaustiveness-checked, so an unhandled kind must be loud
            // rather than silently approving or rejecting a release.
            default -> throw new IllegalStateException(
                    "Unhandled deployment decision kind: " + decision.kind());
        }
    }

    /** The environment's plan override wins over the pipeline's. */
    private DeploymentDecisionInput toInput(DeploymentRequestEntity request,
                                            DeploymentPipelineEntity pipeline,
                                            DeploymentEnvironmentEntity environment,
                                            AiOutcome aiOutcome, RiskLevel riskLevel) {
        var planId = environment.getReviewPlanId() != null
                ? environment.getReviewPlanId() : pipeline.getReviewPlanId();
        return new DeploymentDecisionInput(request.getOrganizationId(), pipeline.getId(),
                pipeline.getProvider(), environment.getName(), environment.isRequireReview(),
                environment.getRequiredApprovals(), planId, request.getVersion(), aiOutcome,
                riskLevel, clock.instant());
    }

    private void approve(DeploymentRequestEntity request, UUID policyId) {
        stateService.apply(request, QueryStatus.APPROVED);
        auditDecision(request, AuditAction.DEPLOYMENT_APPROVED, policyId);
        eventPublisher.publishEvent(new DeploymentDecidedEvent(request.getId(), QueryStatus.APPROVED,
                policyId != null ? "routing:" + policyId : null));
    }

    /** #695: system decisions audit with a null actor and a trigger naming the deciding mechanism. */
    private void auditDecision(DeploymentRequestEntity request, AuditAction action, UUID policyId) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("pipeline_id", request.getPipelineId().toString());
        metadata.put("version", request.getVersion());
        if (policyId != null) {
            metadata.put("trigger", "routing");
            metadata.put("policy_id", policyId.toString());
        } else {
            // Approved without routing: the environment needs no review (or the plan waives it).
            metadata.put("trigger", "environment_policy");
        }
        auditWriter.record(action, AuditResourceType.DEPLOYMENT_REQUEST, request.getId(),
                request.getOrganizationId(), null, metadata, null);
    }

    private void routeToReview(DeploymentRequestEntity request, int requiredApprovals) {
        request.setRequiredApprovals(Math.max(1, requiredApprovals));
        stateService.apply(request, QueryStatus.PENDING_REVIEW);
    }
}
