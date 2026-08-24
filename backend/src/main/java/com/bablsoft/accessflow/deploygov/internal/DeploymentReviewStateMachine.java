package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
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
import com.bablsoft.accessflow.deploygov.internal.routing.DeploymentRoutingPolicyEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Decides what happens to a deployment once AI analysis has completed, been skipped, or failed.
 * Routing policy is consulted first and wins outright; otherwise the target environment's
 * {@code require_review} flag and review plan decide. A failed analysis <strong>always</strong>
 * routes to human review — it never reaches the routing engine, so a provider outage can neither
 * auto-approve nor auto-reject a deployment.
 */
@Component
@RequiredArgsConstructor
class DeploymentReviewStateMachine {

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentEnvironmentRepository environmentRepository;
    private final DeploymentRoutingPolicyEngine routingEngine;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final DeploymentRequestStateService stateService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @ApplicationModuleListener
    void onCompleted(DeploymentAnalysisCompletedEvent event) {
        decide(event.deploymentRequestId(), event.riskLevel());
    }

    @ApplicationModuleListener
    void onSkipped(DeploymentAnalysisSkippedEvent event) {
        decide(event.deploymentRequestId(), null);
    }

    @ApplicationModuleListener
    void onFailed(DeploymentAnalysisFailedEvent event) {
        forceReview(event.deploymentRequestId());
    }

    @Transactional
    void decide(UUID deploymentRequestId, RiskLevel riskLevel) {
        var request = requestRepository.findById(deploymentRequestId).orElse(null);
        if (request == null || request.getStatus() != QueryStatus.PENDING_AI) {
            return;
        }
        var pipeline = pipelineRepository.findById(request.getPipelineId()).orElse(null);
        var environment = environmentRepository.findById(request.getEnvironmentId()).orElse(null);
        if (pipeline == null || environment == null) {
            routeToReview(request, 1);
            return;
        }
        var plan = resolvePlan(pipeline, environment);
        var match = routingEngine.evaluate(request.getOrganizationId(), pipeline.getId(),
                new DeploymentRoutingPolicyEngine.RoutingContext(environment.getName(),
                        pipeline.getProvider(), request.getVersion(), riskLevel, clock.instant()));
        if (match != null) {
            applyRouting(request, environment, plan, match);
            return;
        }
        boolean needsReview = environment.isRequireReview();
        if (plan != null && !plan.requiresHumanApproval()) {
            needsReview = false;
        }
        if (needsReview) {
            routeToReview(request, baseApprovals(environment, plan));
        } else {
            approve(request, null);
        }
    }

    @Transactional
    void forceReview(UUID deploymentRequestId) {
        var request = requestRepository.findById(deploymentRequestId).orElse(null);
        if (request == null || request.getStatus() != QueryStatus.PENDING_AI) {
            return;
        }
        var pipeline = pipelineRepository.findById(request.getPipelineId()).orElse(null);
        var environment = environmentRepository.findById(request.getEnvironmentId()).orElse(null);
        var plan = pipeline == null || environment == null ? null : resolvePlan(pipeline, environment);
        routeToReview(request, baseApprovals(environment, plan));
    }

    private void applyRouting(DeploymentRequestEntity request, DeploymentEnvironmentEntity environment,
                              ReviewPlanSnapshot plan, DeploymentRoutingPolicyEngine.RoutingMatch match) {
        switch (match.action()) {
            case AUTO_APPROVE -> approve(request, match.policyId());
            case AUTO_REJECT -> {
                stateService.apply(request, QueryStatus.REJECTED);
                eventPublisher.publishEvent(new DeploymentDecidedEvent(request.getId(),
                        QueryStatus.REJECTED, "routing:" + match.policyId()));
            }
            // REQUIRE_APPROVALS replaces the resolved count; ESCALATE adds to it. Same arithmetic as
            // apigov's ApiReviewStateMachine, so the two governed surfaces agree.
            case REQUIRE_APPROVALS ->
                    routeToReview(request, match.requiredApprovals() != null
                            ? match.requiredApprovals() : 1);
            case ESCALATE -> routeToReview(request, baseApprovals(environment, plan)
                    + (match.requiredApprovals() != null ? match.requiredApprovals() : 1));
            // A routing action this module does not know yet must never auto-approve or
            // auto-reject; send it to human review, like apigov does.
            default -> routeToReview(request, baseApprovals(environment, plan));
        }
    }

    private void approve(DeploymentRequestEntity request, UUID policyId) {
        stateService.apply(request, QueryStatus.APPROVED);
        eventPublisher.publishEvent(new DeploymentDecidedEvent(request.getId(), QueryStatus.APPROVED,
                policyId != null ? "routing:" + policyId : null));
    }

    private void routeToReview(DeploymentRequestEntity request, int requiredApprovals) {
        request.setRequiredApprovals(Math.max(1, requiredApprovals));
        stateService.apply(request, QueryStatus.PENDING_REVIEW);
    }

    /**
     * Approval count precedence: the environment's own override, else the resolved review plan's
     * minimum, else one. {@code deployment_environments.required_approvals} exists precisely to
     * override the pipeline plan's count for a single environment.
     */
    private static int baseApprovals(DeploymentEnvironmentEntity environment, ReviewPlanSnapshot plan) {
        if (environment != null && environment.getRequiredApprovals() != null) {
            return environment.getRequiredApprovals();
        }
        return plan != null ? plan.minApprovalsRequired() : 1;
    }

    /** The environment's plan override wins over the pipeline's. */
    private ReviewPlanSnapshot resolvePlan(DeploymentPipelineEntity pipeline,
                                           DeploymentEnvironmentEntity environment) {
        var planId = environment.getReviewPlanId() != null
                ? environment.getReviewPlanId() : pipeline.getReviewPlanId();
        if (planId == null) {
            return null;
        }
        return reviewPlanLookupService.findById(planId).orElse(null);
    }
}
