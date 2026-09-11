package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.events.ApiAnalysisCompletedEvent;
import com.bablsoft.accessflow.apigov.events.ApiAnalysisFailedEvent;
import com.bablsoft.accessflow.apigov.events.ApiAnalysisSkippedEvent;
import com.bablsoft.accessflow.apigov.events.ApiRequestDecidedEvent;
import com.bablsoft.accessflow.apigov.events.ApiRequestReadyForReviewEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Applies what {@link ApiDecisionEvaluator} decided for an API request: the status transition, the
 * approval count, and the event that fans the outcome out.
 *
 * <p>Since issue AF-967 this class only <em>applies</em>. Every rule about routing policies, the
 * connector's require-review flags and the review plan lives in the evaluator, so the decision
 * explainer can replay it without any of the side effects below.
 */
@Component
@RequiredArgsConstructor
class ApiReviewStateMachine {

    private final ApiRequestRepository requestRepository;
    private final ApiConnectorRepository connectorRepository;
    private final ApiDecisionEvaluator decisionEvaluator;
    private final ApiRequestStateService stateService;
    private final ApplicationEventPublisher eventPublisher;

    @ApplicationModuleListener
    void onCompleted(ApiAnalysisCompletedEvent event) {
        decide(event.apiRequestId(), AiOutcome.COMPLETED, event.riskLevel());
    }

    @ApplicationModuleListener
    void onSkipped(ApiAnalysisSkippedEvent event) {
        decide(event.apiRequestId(), AiOutcome.SKIPPED, null);
    }

    @ApplicationModuleListener
    void onFailed(ApiAnalysisFailedEvent event) {
        decide(event.apiRequestId(), AiOutcome.FAILED, null);
    }

    @Transactional
    void decide(UUID apiRequestId, AiOutcome aiOutcome, RiskLevel riskLevel) {
        var request = requestRepository.findById(apiRequestId).orElse(null);
        if (request == null || request.getStatus() != QueryStatus.PENDING_AI) {
            return;
        }
        var connector = connectorRepository.findById(request.getConnectorId()).orElse(null);
        if (connector == null) {
            // A connector deleted between submission and analysis leaves no policy to consult, so
            // the call falls back to a single human approval rather than being decided by default.
            routeToReview(request, 1);
            return;
        }
        apply(request, decisionEvaluator.evaluate(toInput(request, connector, aiOutcome, riskLevel)));
    }

    private void apply(ApiRequestEntity request, ApiDecision decision) {
        switch (decision.kind()) {
            case ROUTING_AUTO_APPROVE -> approve(request, decision.routingMatch().policyId());
            case ROUTING_AUTO_REJECT -> {
                stateService.apply(request, QueryStatus.REJECTED);
                eventPublisher.publishEvent(new ApiRequestDecidedEvent(request.getId(),
                        QueryStatus.REJECTED, "routing:" + decision.routingMatch().policyId()));
            }
            case ROUTING_REQUIRE_APPROVALS, ROUTING_ESCALATE, CONNECTOR_PENDING_REVIEW,
                 AI_FAILED_PENDING_REVIEW ->
                    routeToReview(request, decision.effectiveApprovals() != null
                            ? decision.effectiveApprovals() : 1);
            case CONNECTOR_APPROVED -> approve(request, null);
            // A switch statement is not exhaustiveness-checked, so an unhandled kind must be loud
            // rather than silently approving or rejecting a call.
            default -> throw new IllegalStateException(
                    "Unhandled API decision kind: " + decision.kind());
        }
    }

    private static ApiDecisionInput toInput(ApiRequestEntity request, ApiConnectorEntity connector,
                                            AiOutcome aiOutcome, RiskLevel riskLevel) {
        return new ApiDecisionInput(request.getOrganizationId(), connector.getId(),
                connector.getReviewPlanId(), connector.isRequireReviewReads(),
                connector.isRequireReviewWrites(), request.getVerb(), request.isWrite(),
                request.getOperationId(), aiOutcome, riskLevel);
    }

    private void approve(ApiRequestEntity request, UUID policyId) {
        stateService.apply(request, QueryStatus.APPROVED);
        eventPublisher.publishEvent(new ApiRequestDecidedEvent(request.getId(), QueryStatus.APPROVED,
                policyId != null ? "routing:" + policyId : null));
    }

    private void routeToReview(ApiRequestEntity request, int requiredApprovals) {
        request.setRequiredApprovals(Math.max(1, requiredApprovals));
        stateService.apply(request, QueryStatus.PENDING_REVIEW);
        eventPublisher.publishEvent(new ApiRequestReadyForReviewEvent(request.getId(),
                request.getRequiredApprovals()));
    }
}
