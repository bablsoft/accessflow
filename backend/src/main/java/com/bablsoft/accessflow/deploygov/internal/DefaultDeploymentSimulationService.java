package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.deploygov.api.DeploymentDecisionStepKind;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.deploygov.api.DeploymentSimulationInput;
import com.bablsoft.accessflow.deploygov.api.DeploymentSimulationResult;
import com.bablsoft.accessflow.deploygov.api.DeploymentSimulationService;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.internal.routing.DeploymentRoutingPolicyEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The deployment decision trace (issue AF-967): why one hypothetical release would be decided — and
 * gated — the way it would.
 *
 * <p>Stages 4 and 5 are delegated to {@link DeploymentDecisionEvaluator} — the same object the live
 * listener drives — and the rest are reconstructed here from the same collaborators production uses:
 * {@link EffectiveDeploymentPermissionResolver} for the trigger grant, {@link FreezeWindowEvaluator}
 * for the freeze, and {@link DefaultDeploymentGateService#releasable} for the gate. That last one
 * matters most: a release is blocked by the gate rather than by the decision, so a trace that stopped
 * at the decision would answer the wrong question.
 *
 * <p><strong>Read-only by construction.</strong> Every collaborator is a lookup or a pure evaluator:
 * no repository, no state service, no event publisher, no analyzer, no audit writer.
 * {@code DefaultDeploymentSimulationServiceTest} asserts that against the declared field types, so the
 * guarantee survives a future change that would otherwise quietly break it.
 */
@Service
@RequiredArgsConstructor
class DefaultDeploymentSimulationService implements DeploymentSimulationService {

    private final DeploymentPipelineLookupService pipelineLookupService;
    private final EffectiveDeploymentPermissionResolver permissionResolver;
    private final FreezeWindowEvaluator freezeWindowEvaluator;
    private final DeploymentDecisionEvaluator decisionEvaluator;
    private final DeploymentRoutingPolicyEngine routingEngine;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final RolePermissionHolderLookupService rolePermissionHolderLookupService;
    private final UserQueryService userQueryService;
    private final Clock clock;

    /**
     * Deliberately not {@code @Transactional}: this is an aggregation of independently transactional
     * reads, and wrapping it would let one handled exception from an inner call mark the whole read
     * rollback-only. The same reasoning as the other two simulators.
     */
    @Override
    public DeploymentSimulationResult simulate(UUID organizationId,
                                               DeploymentSimulationInput input) {
        var at = input.at() != null ? input.at() : clock.instant();
        var user = userQueryService.findById(input.userId())
                .filter(u -> organizationId.equals(u.organizationId()))
                .orElseThrow(() -> new UserNotFoundException(input.userId()));
        var pipeline = pipelineLookupService.findPipeline(input.pipelineId(), organizationId)
                .orElseThrow(() -> new DeploymentPipelineNotFoundException(input.pipelineId()));
        var environment = pipelineLookupService
                .findEnvironment(pipeline.id(), input.environmentId())
                .orElseThrow(() -> new DeploymentEnvironmentNotFoundException(
                        input.environmentId()));

        var steps = new ArrayList<DecisionTraceStep>(DeploymentDecisionStepKind.values().length);
        var caveats = EnumSet.noneOf(SimulationCaveat.class);
        var permission = permissionResolver.resolve(pipeline.id(), input.userId()).orElse(null);

        steps.add(pipelineStep(pipeline, environment));
        boolean triggerable = triggerStep(permission, steps);
        var freeze = freezeWindowEvaluator.evaluate(organizationId, pipeline.id(), environment.id(),
                at).orElse(null);
        steps.add(freezeStep(freeze));
        if (!pipeline.active() || !triggerable
                || (freeze != null && freeze.behavior() == FreezeBehavior.REJECT)) {
            // A REJECT window refuses at the trigger, exactly as DefaultDeploymentRequestService
            // does — the request never reaches AI analysis, so nothing downstream is evaluated.
            return blocked(steps, caveats, at);
        }

        var decisionInput = new DeploymentDecisionInput(organizationId, pipeline.id(),
                pipeline.provider(), environment.name(), environment.requireReview(),
                environment.requiredApprovals(), planId(pipeline, environment), input.version(),
                input.aiOutcome(), input.riskLevel(), at);
        var decision = decisionEvaluator.evaluate(decisionInput);
        steps.addAll(withFullPolicyList(decision, decisionInput));

        steps.add(reviewerStep(user, environment, pipeline, input, decision.nextStatus()));
        steps.add(scheduleStep(input.scheduledFor(), at));
        // The gate's own function, not a copy: a HOLD window and a REJECT window both count as
        // frozen at release time, which is why `freeze != null` rather than a behaviour check.
        boolean releasable = DefaultDeploymentGateService.releasable(decision.nextStatus(),
                freeze != null, input.scheduledFor(), at);
        steps.add(gateStep(releasable, decision.nextStatus(), freeze != null, input.scheduledFor()));
        steps.add(breakGlassStep(permission, environment));
        return new DeploymentSimulationResult(steps, decision.nextStatus(), releasable, at,
                List.copyOf(caveats));
    }

    private static UUID planId(DeploymentPipelineView pipeline, DeploymentEnvironmentView env) {
        return env.reviewPlanId() != null ? env.reviewPlanId() : pipeline.reviewPlanId();
    }

    // ── 1. Pipeline gates ─────────────────────────────────────────────────────

    private static DecisionTraceStep pipelineStep(DeploymentPipelineView pipeline,
                                                  DeploymentEnvironmentView environment) {
        var details = new LinkedHashMap<String, Object>();
        details.put("pipeline_name", pipeline.name());
        details.put("provider", pipeline.provider().name());
        details.put("active", pipeline.active());
        details.put("environment_name", environment.name());
        details.put("ai_analysis_enabled", pipeline.aiAnalysisEnabled());
        return DecisionTraceStep.of(DeploymentDecisionStepKind.PIPELINE_GATES,
                pipeline.active() ? StepOutcome.ALLOW : StepOutcome.DENY,
                pipeline.active() ? "deploygov.simulation.pipeline.allowed"
                                  : "deploygov.simulation.pipeline.inactive",
                details);
    }

    // ── 2. Trigger permission ─────────────────────────────────────────────────

    /** @return false when the trigger would be refused at the permission gate */
    private static boolean triggerStep(EffectiveDeploymentPermission permission,
                                       List<DecisionTraceStep> steps) {
        boolean canTrigger = permission != null && permission.canTrigger();
        var details = new LinkedHashMap<String, Object>();
        details.put("can_trigger", canTrigger);
        // An org admin's QUERY_ADMIN-style bypass exists on the trigger path (command.admin()), but
        // it is a property of the caller of the trigger endpoint, not of the simulated user, so the
        // trace reports the grant rather than inventing a bypass the CI key would not have.
        details.put("admin_bypass", false);
        details.put("expires_at", permission == null ? null : permission.expiresAt());
        steps.add(DecisionTraceStep.of(DeploymentDecisionStepKind.TRIGGER_PERMISSION,
                canTrigger ? StepOutcome.ALLOW : StepOutcome.DENY,
                canTrigger ? "deploygov.simulation.trigger.allowed"
                        : permission == null ? "deploygov.simulation.trigger.none"
                                             : "deploygov.simulation.trigger.not_granted",
                details));
        return canTrigger;
    }

    // ── 3. Freeze window ──────────────────────────────────────────────────────

    /**
     * A {@code REJECT} window refuses the trigger outright; a {@code HOLD} lets it through and
     * withholds releasability later. Both are reported here, because an admin asking "why is my
     * release stuck" needs to see the window even when it did not stop the submission. A window whose
     * stored definition cannot be evaluated counts as an active {@code HOLD} — fail-closed — and is
     * reported as such rather than as "no freeze".
     */
    private static DecisionTraceStep freezeStep(FreezeWindowEvaluator.ActiveFreeze freeze) {
        if (freeze == null) {
            return DecisionTraceStep.of(DeploymentDecisionStepKind.FREEZE_WINDOW,
                    StepOutcome.NO_MATCH, "deploygov.simulation.freeze.none");
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("freeze_window_id", freeze.windowId());
        details.put("behavior", freeze.behavior().name());
        details.put("reason_text", freeze.reason());
        details.put("scope", scopeOf(freeze.specificity()));
        return DecisionTraceStep.of(DeploymentDecisionStepKind.FREEZE_WINDOW,
                freeze.behavior() == FreezeBehavior.REJECT ? StepOutcome.DENY : StepOutcome.MATCH,
                freeze.behavior() == FreezeBehavior.REJECT
                        ? "deploygov.simulation.freeze.reject"
                        : "deploygov.simulation.freeze.hold",
                details);
    }

    private static String scopeOf(int specificity) {
        return switch (specificity) {
            case 2 -> "ENVIRONMENT";
            case 1 -> "PIPELINE";
            default -> "ORGANIZATION";
        };
    }

    // ── 4-5. Routing and the environment policy, with every policy listed ─────

    /**
     * The evaluator short-circuits at the first matching policy, which is right for routing and
     * useless for explaining. Re-running {@code evaluateAll} through the engine's own matcher attaches
     * the full ordered list — on this kind that is especially valuable for the time-window leaf, which
     * appears on no other screen.
     */
    private List<DecisionTraceStep> withFullPolicyList(DeploymentDecision decision,
                                                       DeploymentDecisionInput decisionInput) {
        if (decision.kind() == DeploymentDecisionKind.AI_FAILED_PENDING_REVIEW) {
            // Production never routes a failed analysis, so there is no evaluation to report.
            return decision.trace().steps();
        }
        var risk = decisionInput.aiOutcome() == AiOutcome.COMPLETED ? decisionInput.riskLevel() : null;
        var context = new DeploymentRoutingPolicyEngine.RoutingContext(
                decisionInput.environmentName(), decisionInput.provider(), decisionInput.version(),
                risk, decisionInput.at());
        var evaluations = routingEngine.evaluateAll(decisionInput.organizationId(),
                decisionInput.pipelineId(), context);
        // "decisive" comes from the decision itself, not from this second evaluation's own first
        // match, so the presentational list can never name a different winner than the one that
        // decided.
        var decidedBy = decision.routingMatch() == null ? null : decision.routingMatch().policyId();
        var policies = evaluations.stream().map(e -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("policy_id", e.policyId());
            entry.put("name", e.name());
            entry.put("priority", e.priority());
            entry.put("action", e.action().name());
            entry.put("required_approvals", e.requiredApprovals());
            entry.put("matched", e.matched());
            entry.put("decisive", e.policyId() != null && e.policyId().equals(decidedBy));
            return (Object) entry;
        }).toList();
        return decision.trace().steps().stream()
                .map(step -> step.step() == DeploymentDecisionStepKind.ROUTING_POLICIES
                        ? withPolicies(step, policies)
                        : step)
                .toList();
    }

    private static DecisionTraceStep withPolicies(DecisionTraceStep step, List<Object> policies) {
        var details = new LinkedHashMap<>(step.details());
        details.put("policies", policies);
        return new DecisionTraceStep(step.step(), step.outcome(), step.reasonKey(),
                step.reasonArgs(), details);
    }

    // ── 6. Eligible reviewers ─────────────────────────────────────────────────

    /**
     * Eligibility is opt-in by configuration, exactly as {@code DefaultDeploymentReviewService} has
     * it: a plan naming approvers narrows the request to them, and otherwise it stays open to any
     * {@code DEPLOYMENT_REVIEW} holder. Review delegation (#622) deliberately does not extend to
     * deployments, so unlike the query explainer there is nothing further to caveat.
     */
    private DecisionTraceStep reviewerStep(UserView user, DeploymentEnvironmentView environment,
                                           DeploymentPipelineView pipeline,
                                           DeploymentSimulationInput input,
                                           QueryStatus resultingStatus) {
        if (resultingStatus != QueryStatus.PENDING_REVIEW) {
            return DecisionTraceStep.of(DeploymentDecisionStepKind.ELIGIBLE_REVIEWERS,
                    StepOutcome.SKIP, "deploygov.simulation.reviewers.not_pending_review");
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("submitter_excluded", true);
        var planReference = planId(pipeline, environment);
        var plan = planReference == null ? null
                : reviewPlanLookupService.findById(planReference).orElse(null);
        if (plan != null && !plan.approvers().isEmpty()) {
            details.put("plan_approvers", approverRules(plan, input.userId()));
            return DecisionTraceStep.of(DeploymentDecisionStepKind.ELIGIBLE_REVIEWERS,
                    StepOutcome.ALLOW, "deploygov.simulation.reviewers.plan_approvers", details);
        }
        // Security rule: a user can never approve their own deployment, whatever else they hold —
        // including an admin, and including the API key's owning user.
        var reviewerIds = rolePermissionHolderLookupService
                .findUserIdsWithPermission(user.organizationId(), Permission.DEPLOYMENT_REVIEW)
                .stream()
                .filter(id -> !id.equals(input.userId()))
                .toList();
        details.put("reviewers", userQueryService.findByIds(reviewerIds).stream()
                .filter(UserView::active)
                .map(DefaultDeploymentSimulationService::describeReviewer)
                .toList());
        boolean none = reviewerIds.isEmpty();
        return DecisionTraceStep.of(DeploymentDecisionStepKind.ELIGIBLE_REVIEWERS,
                none ? StepOutcome.DENY : StepOutcome.ALLOW,
                none ? "deploygov.simulation.reviewers.none"
                     : "deploygov.simulation.reviewers.permission_holders",
                details);
    }

    private static List<Object> approverRules(ReviewPlanSnapshot plan, UUID submitterId) {
        return plan.approvers().stream()
                .filter(rule -> !submitterId.equals(rule.userId()))
                .map(rule -> {
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("user_id", rule.userId());
                    entry.put("role", rule.role());
                    entry.put("stage", rule.stage());
                    return (Object) entry;
                })
                .toList();
    }

    private static Map<String, Object> describeReviewer(UserView user) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("user_id", user.id());
        entry.put("email", user.email());
        entry.put("display_name", user.displayName());
        return entry;
    }

    // ── 7. Scheduled release ──────────────────────────────────────────────────

    private static DecisionTraceStep scheduleStep(Instant scheduledFor, Instant at) {
        var details = new LinkedHashMap<String, Object>();
        details.put("scheduled_for", scheduledFor);
        details.put("evaluated_at", at);
        if (scheduledFor == null) {
            return DecisionTraceStep.of(DeploymentDecisionStepKind.SCHEDULED_RELEASE,
                    StepOutcome.ALLOW, "deploygov.simulation.schedule.immediate", details);
        }
        boolean due = !scheduledFor.isAfter(at);
        return DecisionTraceStep.of(DeploymentDecisionStepKind.SCHEDULED_RELEASE,
                due ? StepOutcome.ALLOW : StepOutcome.DENY,
                due ? "deploygov.simulation.schedule.due" : "deploygov.simulation.schedule.pending",
                details);
    }

    // ── 8. The gate ───────────────────────────────────────────────────────────

    private static DecisionTraceStep gateStep(boolean releasable, QueryStatus status, boolean frozen,
                                              Instant scheduledFor) {
        var details = new LinkedHashMap<String, Object>();
        details.put("releasable", releasable);
        details.put("status", status == null ? null : status.name());
        details.put("frozen", frozen);
        details.put("scheduled_for", scheduledFor);
        return DecisionTraceStep.of(DeploymentDecisionStepKind.GATE_RELEASABILITY,
                releasable ? StepOutcome.ALLOW : StepOutcome.DENY,
                releasable ? "deploygov.simulation.gate.releasable"
                           : "deploygov.simulation.gate.not_releasable",
                details);
    }

    // ── 9. Break-glass ────────────────────────────────────────────────────────

    /** Gated twice, with no admin bypass: the grant <em>and</em> the environment's opt-in. */
    private static DecisionTraceStep breakGlassStep(EffectiveDeploymentPermission permission,
                                                    DeploymentEnvironmentView environment) {
        boolean granted = permission != null && permission.canBreakGlass();
        boolean allowed = environment.allowBreakGlass();
        var details = new LinkedHashMap<String, Object>();
        details.put("can_break_glass", granted);
        details.put("environment_allows_break_glass", allowed);
        String reasonKey;
        if (granted && allowed) {
            reasonKey = "deploygov.simulation.break_glass.eligible";
        } else if (!allowed) {
            reasonKey = "deploygov.simulation.break_glass.environment_forbids";
        } else {
            reasonKey = "deploygov.simulation.break_glass.not_granted";
        }
        return DecisionTraceStep.of(DeploymentDecisionStepKind.BREAK_GLASS,
                granted && allowed ? StepOutcome.ALLOW : StepOutcome.DENY, reasonKey, details);
    }

    // ── Assembly ──────────────────────────────────────────────────────────────

    /**
     * A deployment blocked before the decision chain still reports every stage, so a client renders
     * one stable checklist rather than a list whose length encodes how far the request got.
     */
    private static DeploymentSimulationResult blocked(List<DecisionTraceStep> steps,
                                                      Set<SimulationCaveat> caveats, Instant at) {
        var reached = new LinkedHashMap<Object, DecisionTraceStep>();
        steps.forEach(step -> reached.putIfAbsent(step.step(), step));
        var full = new ArrayList<DecisionTraceStep>(DeploymentDecisionStepKind.values().length);
        for (var kind : DeploymentDecisionStepKind.values()) {
            var step = reached.get(kind);
            full.add(step != null ? step
                    : DecisionTraceStep.of(kind, StepOutcome.SKIP,
                            "deploygov.simulation.not_reached"));
        }
        return new DeploymentSimulationResult(full, null, false, at, List.copyOf(caveats));
    }
}
