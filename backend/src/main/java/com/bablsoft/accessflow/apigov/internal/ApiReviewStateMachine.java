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
import com.bablsoft.accessflow.apigov.internal.routing.ApiRoutingPolicyEngine;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reacts to AI analysis outcomes for an API request and decides its next status: routing policy
 * first (AUTO_APPROVE / AUTO_REJECT / REQUIRE_APPROVALS / ESCALATE), then the connector's
 * require-review flags + review plan. A failed analysis fails safe to human review.
 */
@Component
@RequiredArgsConstructor
class ApiReviewStateMachine {

    private final ApiRequestRepository requestRepository;
    private final ApiConnectorRepository connectorRepository;
    private final ApiRoutingPolicyEngine routingEngine;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ApiRequestStateService stateService;
    private final ApplicationEventPublisher eventPublisher;

    @ApplicationModuleListener
    void onCompleted(ApiAnalysisCompletedEvent event) {
        decide(event.apiRequestId(), event.riskLevel());
    }

    @ApplicationModuleListener
    void onSkipped(ApiAnalysisSkippedEvent event) {
        decide(event.apiRequestId(), null);
    }

    @ApplicationModuleListener
    void onFailed(ApiAnalysisFailedEvent event) {
        forceReview(event.apiRequestId());
    }

    @Transactional
    void decide(UUID apiRequestId, RiskLevel riskLevel) {
        var request = requestRepository.findById(apiRequestId).orElse(null);
        if (request == null || request.getStatus() != QueryStatus.PENDING_AI) {
            return;
        }
        var connector = connectorRepository.findById(request.getConnectorId()).orElse(null);
        if (connector == null) {
            routeToReview(request, 1);
            return;
        }
        var match = routingEngine.evaluate(request.getOrganizationId(), connector.getId(),
                new ApiRoutingPolicyEngine.RoutingContext(request.getVerb(), request.isWrite(),
                        request.getOperationId(), riskLevel));
        var plan = resolvePlan(connector);
        if (match != null) {
            applyRouting(request, plan, match);
            return;
        }
        boolean needsReview = request.isWrite() ? connector.isRequireReviewWrites()
                : connector.isRequireReviewReads();
        if (plan != null && !plan.requiresHumanApproval()) {
            needsReview = false;
        }
        if (needsReview) {
            routeToReview(request, plan != null ? plan.minApprovalsRequired() : 1);
        } else {
            approve(request, null);
        }
    }

    @Transactional
    void forceReview(UUID apiRequestId) {
        var request = requestRepository.findById(apiRequestId).orElse(null);
        if (request == null || request.getStatus() != QueryStatus.PENDING_AI) {
            return;
        }
        var plan = resolvePlan(connectorRepository.findById(request.getConnectorId()).orElse(null));
        routeToReview(request, plan != null ? plan.minApprovalsRequired() : 1);
    }

    private void applyRouting(ApiRequestEntity request, ReviewPlanSnapshot plan,
                             ApiRoutingPolicyEngine.RoutingMatch match) {
        switch (match.action()) {
            case AUTO_APPROVE -> approve(request, match.policyId());
            case AUTO_REJECT -> {
                stateService.apply(request, QueryStatus.REJECTED);
                eventPublisher.publishEvent(new ApiRequestDecidedEvent(request.getId(),
                        QueryStatus.REJECTED, "routing:" + match.policyId()));
            }
            case REQUIRE_APPROVALS ->
                    routeToReview(request, match.requiredApprovals() != null ? match.requiredApprovals() : 1);
            case ESCALATE -> {
                int base = plan != null ? plan.minApprovalsRequired() : 1;
                routeToReview(request, base + (match.requiredApprovals() != null ? match.requiredApprovals() : 1));
            }
            default -> routeToReview(request, 1);
        }
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

    private ReviewPlanSnapshot resolvePlan(ApiConnectorEntity connector) {
        if (connector == null || connector.getReviewPlanId() == null) {
            return null;
        }
        return reviewPlanLookupService.findById(connector.getReviewPlanId()).orElse(null);
    }
}
