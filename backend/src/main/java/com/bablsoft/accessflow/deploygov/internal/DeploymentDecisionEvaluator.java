package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.DecisionTrace;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentDecisionStepKind;
import com.bablsoft.accessflow.deploygov.internal.routing.DeploymentRoutingPolicyEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Decides what should happen to a deployment leaving {@code PENDING_AI}, and records why — without
 * doing any of it (issue AF-967).
 *
 * <p>The chain is the one production has always run: routing policies first (first enabled policy by
 * ascending priority whose condition matches wins, outright over the environment's own policy), then
 * the environment's {@code require_review} flag folded with the resolved review plan. What changed is
 * that deciding and applying are no longer interleaved. {@link DeploymentReviewStateMachine} applies
 * the {@link DeploymentDecision}; the deployment simulator replays a hypothetical release through this
 * same method. A simulator with its own copy of these rules would disagree with enforcement the first
 * time either side changed, which would make it worse than useless.
 *
 * <p>This class reads, and only reads: no transition, no persistence, no published event, no audit
 * row, no AI call.
 */
@Component
@RequiredArgsConstructor
class DeploymentDecisionEvaluator {

    private final DeploymentRoutingPolicyEngine routingEngine;
    private final ReviewPlanLookupService reviewPlanLookupService;

    DeploymentDecision evaluate(DeploymentDecisionInput input) {
        var plan = resolvePlan(input);
        if (input.aiOutcome() == AiOutcome.FAILED) {
            return aiFailed(input, plan);
        }
        // Only a COMPLETED analysis carries a risk signal. Normalising here rather than trusting the
        // caller keeps the SKIPPED branch identical to production, where the listener passes no risk
        // at all, and stops a simulated verdict from leaking into a branch that never sees one.
        var risk = input.aiOutcome() == AiOutcome.COMPLETED ? input.riskLevel() : null;
        var context = new DeploymentRoutingPolicyEngine.RoutingContext(input.environmentName(),
                input.provider(), input.version(), risk, input.at());
        var steps = new ArrayList<DecisionTraceStep>(2);

        var match = routingEngine.evaluate(input.organizationId(), input.pipelineId(), context);
        if (match != null) {
            return routed(input, match, plan, steps);
        }
        steps.add(DecisionTraceStep.of(DeploymentDecisionStepKind.ROUTING_POLICIES,
                StepOutcome.NO_MATCH, "deploygov.decision.routing.no_match"));
        return byEnvironmentPolicy(input, plan, steps);
    }

    /**
     * AI failure lands in {@code PENDING_REVIEW} unconditionally so a human can inspect the release.
     * Routing does not run — there is no risk signal, and a failed analysis is not a positive
     * auto-decision signal — so a provider outage can neither auto-approve nor auto-reject a
     * deployment. The approval count still resolves normally: an outage must not quietly reduce the
     * number of humans a production release needs.
     */
    private DeploymentDecision aiFailed(DeploymentDecisionInput input, ReviewPlanSnapshot plan) {
        int approvals = baseApprovals(input, plan);
        var details = environmentDetails(input, plan);
        details.put("effective_min_approvals", approvals);
        var steps = List.of(
                DecisionTraceStep.of(DeploymentDecisionStepKind.ROUTING_POLICIES, StepOutcome.SKIP,
                        "deploygov.decision.routing.skipped_ai_failed"),
                DecisionTraceStep.of(DeploymentDecisionStepKind.ENVIRONMENT_POLICY, StepOutcome.SKIP,
                        "deploygov.decision.environment.skipped_ai_failed", details));
        return new DeploymentDecision(DeploymentDecisionKind.AI_FAILED_PENDING_REVIEW,
                QueryStatus.PENDING_REVIEW, null, approvals,
                new DecisionTrace(steps, QueryStatus.PENDING_REVIEW));
    }

    private DeploymentDecision routed(DeploymentDecisionInput input,
                                      DeploymentRoutingPolicyEngine.RoutingMatch match,
                                      ReviewPlanSnapshot plan, List<DecisionTraceStep> steps) {
        // REQUIRE_APPROVALS replaces the resolved count; ESCALATE adds to it. Same arithmetic as
        // apigov's ApiDecisionEvaluator, so the two governed surfaces agree.
        var effect = switch (match.action()) {
            case AUTO_APPROVE -> new RoutedEffect(DeploymentDecisionKind.ROUTING_AUTO_APPROVE,
                    QueryStatus.APPROVED, null);
            case AUTO_REJECT -> new RoutedEffect(DeploymentDecisionKind.ROUTING_AUTO_REJECT,
                    QueryStatus.REJECTED, null);
            case REQUIRE_APPROVALS -> new RoutedEffect(
                    DeploymentDecisionKind.ROUTING_REQUIRE_APPROVALS, QueryStatus.PENDING_REVIEW,
                    match.requiredApprovals() != null ? match.requiredApprovals() : 1);
            case ESCALATE -> new RoutedEffect(DeploymentDecisionKind.ROUTING_ESCALATE,
                    QueryStatus.PENDING_REVIEW, baseApprovals(input, plan)
                    + (match.requiredApprovals() != null ? match.requiredApprovals() : 1));
        };
        var details = new LinkedHashMap<String, Object>();
        details.put("matched_policy_id", match.policyId());
        details.put("matched_policy_name", match.policyName());
        details.put("action", match.action().name());
        details.put("effective_min_approvals", effect.effectiveApprovals());
        steps.add(new DecisionTraceStep(DeploymentDecisionStepKind.ROUTING_POLICIES,
                StepOutcome.MATCH, "deploygov.decision.routing.matched",
                List.of(String.valueOf(match.policyName()), match.action().name()), details));
        steps.add(DecisionTraceStep.of(DeploymentDecisionStepKind.ENVIRONMENT_POLICY,
                StepOutcome.SKIP, "deploygov.decision.environment.skipped_routing_decided",
                environmentDetails(input, plan)));
        return new DeploymentDecision(effect.kind(), effect.nextStatus(), match,
                effect.effectiveApprovals(), new DecisionTrace(steps, effect.nextStatus()));
    }

    /** The effect of one matched routing action, so the switch stays an exhaustive expression. */
    private record RoutedEffect(DeploymentDecisionKind kind, QueryStatus nextStatus,
                                Integer effectiveApprovals) {
    }

    private DeploymentDecision byEnvironmentPolicy(DeploymentDecisionInput input,
                                                   ReviewPlanSnapshot plan,
                                                   List<DecisionTraceStep> steps) {
        boolean needsReview = input.environmentRequiresReview();
        String reasonKey = needsReview
                ? "deploygov.decision.environment.requires_review"
                : "deploygov.decision.environment.no_review_required";
        if (plan != null && !plan.requiresHumanApproval()) {
            needsReview = false;
            reasonKey = "deploygov.decision.environment.plan_waives_approval";
        }
        var details = environmentDetails(input, plan);
        if (needsReview) {
            int approvals = baseApprovals(input, plan);
            details.put("effective_min_approvals", approvals);
            steps.add(DecisionTraceStep.of(DeploymentDecisionStepKind.ENVIRONMENT_POLICY,
                    StepOutcome.DENY, reasonKey, details));
            return new DeploymentDecision(DeploymentDecisionKind.ENVIRONMENT_PENDING_REVIEW,
                    QueryStatus.PENDING_REVIEW, null, approvals,
                    new DecisionTrace(steps, QueryStatus.PENDING_REVIEW));
        }
        steps.add(DecisionTraceStep.of(DeploymentDecisionStepKind.ENVIRONMENT_POLICY,
                StepOutcome.ALLOW, reasonKey, details));
        return new DeploymentDecision(DeploymentDecisionKind.ENVIRONMENT_APPROVED,
                QueryStatus.APPROVED, null, null, new DecisionTrace(steps, QueryStatus.APPROVED));
    }

    /** The environment's plan override wins over the pipeline's; the caller resolves which id that is. */
    private ReviewPlanSnapshot resolvePlan(DeploymentDecisionInput input) {
        return input.reviewPlanId() == null ? null
                : reviewPlanLookupService.findById(input.reviewPlanId()).orElse(null);
    }

    /**
     * Approval count precedence: the environment's own override, else the resolved review plan's
     * minimum, else one. {@code deployment_environments.required_approvals} exists precisely to
     * override the pipeline plan's count for a single environment.
     */
    private static int baseApprovals(DeploymentDecisionInput input, ReviewPlanSnapshot plan) {
        if (input.environmentRequiredApprovals() != null) {
            return input.environmentRequiredApprovals();
        }
        return plan != null ? plan.minApprovalsRequired() : 1;
    }

    private static LinkedHashMap<String, Object> environmentDetails(DeploymentDecisionInput input,
                                                                    ReviewPlanSnapshot plan) {
        var details = new LinkedHashMap<String, Object>();
        details.put("require_review", input.environmentRequiresReview());
        details.put("environment_required_approvals", input.environmentRequiredApprovals());
        details.put("review_plan_id", plan == null ? null : plan.id());
        details.put("requires_human_approval", plan == null ? null : plan.requiresHumanApproval());
        details.put("min_approvals_required", plan == null ? null : plan.minApprovalsRequired());
        return details;
    }
}
