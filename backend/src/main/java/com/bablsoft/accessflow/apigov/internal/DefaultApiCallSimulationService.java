package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiCallSimulationInput;
import com.bablsoft.accessflow.apigov.api.ApiCallSimulationResult;
import com.bablsoft.accessflow.apigov.api.ApiCallSimulationService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorGovernanceView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingResolutionService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiDecisionStepKind;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.apigov.api.ResolvedApiMask;
import com.bablsoft.accessflow.apigov.internal.EffectiveApiConnectorPermissionResolver.ResolvedApiConnectorPermission;
import com.bablsoft.accessflow.apigov.internal.routing.ApiRoutingPolicyEngine;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The API-call decision trace (issue AF-967): why one hypothetical call would be decided the way it
 * would.
 *
 * <p>Stages 5 and 6 are delegated to {@link ApiDecisionEvaluator} — the same object the live listener
 * drives — and the rest are reconstructed here, because production evaluates them in the synchronous
 * submission gate, at execution time, or in a separate submission mode. One trace covers the whole
 * journey rather than only the asynchronous slice.
 *
 * <p><strong>Read-only by construction.</strong> Every collaborator below is a lookup or a pure
 * evaluator: there is no repository, no state service, no event publisher, no analyzer, and — the one
 * specific to this module — no HTTP client, so a simulation cannot reach the governed third-party API
 * even by accident. {@code DefaultApiCallSimulationServiceTest} asserts that against the declared
 * field types, so the guarantee survives a future change that would otherwise quietly break it.
 */
@Service
@RequiredArgsConstructor
class DefaultApiCallSimulationService implements ApiCallSimulationService {

    private static final Set<String> MUTATING_VERBS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ApiConnectorLookupService connectorLookupService;
    private final ApiSchemaService schemaService;
    private final EffectiveApiConnectorPermissionResolver permissionResolver;
    private final ApiDecisionEvaluator decisionEvaluator;
    private final ApiRoutingPolicyEngine routingEngine;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ApiConnectorMaskingResolutionService maskingResolutionService;
    private final RolePermissionHolderLookupService rolePermissionHolderLookupService;
    private final UserQueryService userQueryService;

    /**
     * Deliberately not {@code @Transactional}: this is an aggregation of independently transactional
     * reads, and wrapping it would let one handled exception from an inner call mark the whole read
     * rollback-only. The same reasoning as the query-side simulator.
     */
    @Override
    public ApiCallSimulationResult simulate(UUID organizationId, ApiCallSimulationInput input) {
        var user = userQueryService.findById(input.userId())
                .filter(u -> organizationId.equals(u.organizationId()))
                .orElseThrow(() -> new UserNotFoundException(input.userId()));
        var connector = connectorLookupService
                .findGovernanceView(input.connectorId(), organizationId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(input.connectorId()));

        var steps = new ArrayList<DecisionTraceStep>(ApiDecisionStepKind.values().length);
        var caveats = EnumSet.of(SimulationCaveat.RESPONSE_SHAPE_ABSENT);

        steps.add(connectorStep(connector));
        var operations = schemaService.listOperations(connector.id(), organizationId);
        var classification = classify(connector, operations, input);
        steps.add(classificationStep(input, classification));
        boolean schemaOk = schemaStep(input, operations, steps);
        var permission = permissionResolver.resolve(connector.id(), input.userId()).orElse(null);
        boolean permitted = permissionStep(user, input, classification.write(), permission, steps);
        if (!connector.active() || !schemaOk || !permitted) {
            return blocked(steps, caveats);
        }

        var decisionInput = new ApiDecisionInput(organizationId, connector.id(),
                connector.reviewPlanId(), connector.requireReviewReads(),
                connector.requireReviewWrites(), input.verb(), classification.write(),
                input.operationId(), input.aiOutcome(), input.riskLevel());
        var decision = decisionEvaluator.evaluate(decisionInput);
        steps.addAll(withFullPolicyList(decision, decisionInput));

        steps.add(reviewerStep(connector, input, decision.nextStatus()));
        steps.add(maskingStep(organizationId, input, permission));
        steps.add(breakGlassStep(permission));
        return new ApiCallSimulationResult(steps, decision.nextStatus(), List.copyOf(caveats));
    }

    // ── 1. Connector gates ────────────────────────────────────────────────────

    private static DecisionTraceStep connectorStep(ApiConnectorGovernanceView connector) {
        var details = new LinkedHashMap<String, Object>();
        details.put("connector_name", connector.name());
        details.put("protocol", connector.protocol().name());
        details.put("active", connector.active());
        details.put("ai_analysis_enabled", connector.aiAnalysisEnabled());
        return DecisionTraceStep.of(ApiDecisionStepKind.CONNECTOR_GATES,
                connector.active() ? StepOutcome.ALLOW : StepOutcome.DENY,
                connector.active() ? "apigov.simulation.connector.allowed"
                                   : "apigov.simulation.connector.inactive",
                details);
    }

    // ── 2. Write classification ───────────────────────────────────────────────

    /** How the live classifier decided read vs write. Reported so the verdict is falsifiable. */
    private enum ClassificationSource { SCHEMA_OPERATION, REST_VERB, PROTOCOL_DEFAULT }

    private record Classification(boolean write, ClassificationSource source) {
    }

    /**
     * Mirrors {@code DefaultApiRequestService.classifyWrite}: a matching schema operation wins, else
     * a REST connector reads the verb, else the call is treated as a write — SOAP, GraphQL and gRPC
     * fail safe <em>to</em> review rather than away from it.
     */
    private static Classification classify(ApiConnectorGovernanceView connector,
                                           List<ApiOperation> operations,
                                           ApiCallSimulationInput input) {
        if (input.operationId() != null) {
            var match = operations.stream()
                    .filter(o -> input.operationId().equals(o.operationId()))
                    .findFirst()
                    .orElse(null);
            if (match != null) {
                return new Classification(match.write(), ClassificationSource.SCHEMA_OPERATION);
            }
        }
        if (connector.protocol() == ApiProtocol.REST) {
            boolean write = input.verb() != null
                    && MUTATING_VERBS.contains(input.verb().toUpperCase(Locale.ROOT));
            return new Classification(write, ClassificationSource.REST_VERB);
        }
        return new Classification(true, ClassificationSource.PROTOCOL_DEFAULT);
    }

    private static DecisionTraceStep classificationStep(ApiCallSimulationInput input,
                                                        Classification classification) {
        var details = new LinkedHashMap<String, Object>();
        details.put("write", classification.write());
        details.put("classified_from", classification.source().name());
        details.put("operation_id", input.operationId());
        details.put("verb", input.verb());
        var reasonKey = switch (classification.source()) {
            case SCHEMA_OPERATION -> classification.write()
                    ? "apigov.simulation.classification.write_from_schema"
                    : "apigov.simulation.classification.read_from_schema";
            case REST_VERB -> classification.write()
                    ? "apigov.simulation.classification.write_from_verb"
                    : "apigov.simulation.classification.read_from_verb";
            case PROTOCOL_DEFAULT -> "apigov.simulation.classification.write_by_protocol_default";
        };
        // Classification never refuses a call; it only decides which gate the call is measured
        // against. Reporting it as a DENY would read as a refusal that never happens.
        return DecisionTraceStep.of(ApiDecisionStepKind.CALL_CLASSIFICATION, StepOutcome.ALLOW,
                reasonKey, details);
    }

    // ── 3. Schema validation ──────────────────────────────────────────────────

    /** @return false when the call would be refused at the schema gate */
    private static boolean schemaStep(ApiCallSimulationInput input, List<ApiOperation> operations,
                                      List<DecisionTraceStep> steps) {
        if (input.operationId() == null) {
            // A free-form call is accepted by the submit path and routed to review on its merits.
            // Reporting SKIP rather than ALLOW keeps "the catalog was not consulted" distinguishable
            // from "the catalog was consulted and agreed".
            steps.add(DecisionTraceStep.of(ApiDecisionStepKind.SCHEMA_VALIDATION, StepOutcome.SKIP,
                    "apigov.simulation.schema.free_form"));
            return true;
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("operation_id", input.operationId());
        details.put("operation_count", operations.size());
        // A connector with no ingested schema validates nothing — the same rule the submit path
        // applies, so a trace never claims an operation is missing from a catalog that is empty.
        boolean ok = operations.isEmpty()
                || operations.stream().anyMatch(o -> input.operationId().equals(o.operationId()));
        steps.add(DecisionTraceStep.of(ApiDecisionStepKind.SCHEMA_VALIDATION,
                ok ? StepOutcome.ALLOW : StepOutcome.DENY,
                operations.isEmpty() ? "apigov.simulation.schema.no_catalog"
                        : ok ? "apigov.simulation.schema.known_operation"
                             : "apigov.simulation.schema.unknown_operation",
                details));
        return ok;
    }

    // ── 4. Operation permission ───────────────────────────────────────────────

    /** @return false when the call would be refused at the permission gate */
    private boolean permissionStep(UserView user, ApiCallSimulationInput input, boolean write,
                                   ResolvedApiConnectorPermission permission,
                                   List<DecisionTraceStep> steps) {
        var details = new LinkedHashMap<String, Object>();
        // QUERY_ADMIN holders submit against any connector without a per-connector grant, so they
        // appear in no permission table while being able to reach everything. Naming the bypass is
        // the whole reason this key is in the trace.
        boolean queryAdmin = rolePermissionHolderLookupService
                .findUserIdsWithPermission(user.organizationId(), Permission.QUERY_ADMIN)
                .contains(input.userId());
        details.put("query_admin_short_circuit", queryAdmin);
        if (queryAdmin) {
            steps.add(DecisionTraceStep.of(ApiDecisionStepKind.OPERATION_PERMISSION,
                    StepOutcome.ALLOW, "apigov.simulation.permission.query_admin_bypass", details));
            return true;
        }
        if (permission == null) {
            steps.add(DecisionTraceStep.of(ApiDecisionStepKind.OPERATION_PERMISSION,
                    StepOutcome.DENY, "apigov.simulation.permission.none", details));
            return false;
        }
        details.put("can_read", permission.canRead());
        details.put("can_write", permission.canWrite());
        details.put("allowed_operations", permission.allowedOperations());
        details.put("expires_at", permission.expiresAt());
        if (write && !permission.canWrite()) {
            steps.add(DecisionTraceStep.of(ApiDecisionStepKind.OPERATION_PERMISSION,
                    StepOutcome.DENY, "apigov.simulation.permission.write_missing", details));
            return false;
        }
        if (!write && !permission.canRead()) {
            steps.add(DecisionTraceStep.of(ApiDecisionStepKind.OPERATION_PERMISSION,
                    StepOutcome.DENY, "apigov.simulation.permission.read_missing", details));
            return false;
        }
        // An empty allow-list means "every operation", and a free-form call is not scoped by it —
        // both exactly as the submit path treats them.
        if (!permission.allowedOperations().isEmpty() && input.operationId() != null
                && !permission.allowedOperations().contains(input.operationId())) {
            steps.add(DecisionTraceStep.of(ApiDecisionStepKind.OPERATION_PERMISSION,
                    StepOutcome.DENY, "apigov.simulation.permission.operation_not_allowed", details));
            return false;
        }
        steps.add(DecisionTraceStep.of(ApiDecisionStepKind.OPERATION_PERMISSION, StepOutcome.ALLOW,
                "apigov.simulation.permission.allowed", details));
        return true;
    }

    // ── 5-6. Routing and the review requirement, with every policy listed ─────

    /**
     * The evaluator short-circuits at the first matching policy, which is right for routing and
     * useless for explaining. Re-running {@code evaluateAll} through the engine's own matcher attaches
     * the full ordered list — a policy that <em>nearly</em> matched is usually the most useful line in
     * a trace.
     */
    private List<DecisionTraceStep> withFullPolicyList(ApiDecision decision,
                                                       ApiDecisionInput decisionInput) {
        if (decision.kind() == ApiDecisionKind.AI_FAILED_PENDING_REVIEW) {
            // Production never routes a failed analysis, so there is no evaluation to report.
            return decision.trace().steps();
        }
        var risk = decisionInput.aiOutcome() == AiOutcome.COMPLETED ? decisionInput.riskLevel() : null;
        var context = new ApiRoutingPolicyEngine.RoutingContext(decisionInput.verb(),
                decisionInput.write(), decisionInput.operationId(), risk);
        var evaluations = routingEngine.evaluateAll(decisionInput.organizationId(),
                decisionInput.connectorId(), context);
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
                .map(step -> step.step() == ApiDecisionStepKind.ROUTING_POLICIES
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

    // ── 7. Eligible reviewers ─────────────────────────────────────────────────

    private DecisionTraceStep reviewerStep(ApiConnectorGovernanceView connector,
                                           ApiCallSimulationInput input, QueryStatus resultingStatus) {
        if (resultingStatus != QueryStatus.PENDING_REVIEW) {
            return DecisionTraceStep.of(ApiDecisionStepKind.ELIGIBLE_REVIEWERS, StepOutcome.SKIP,
                    "apigov.simulation.reviewers.not_pending_review");
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("submitter_excluded", true);
        var plan = connector.reviewPlanId() == null ? null
                : reviewPlanLookupService.findById(connector.reviewPlanId()).orElse(null);
        if (plan != null && !plan.approvers().isEmpty()) {
            // A plan that names approvers narrows eligibility to them. Those rules are role shapes
            // rather than a resolvable user list, so they are reported as written. A rule naming the
            // submitter is dropped, because the self-approval ban outranks the plan.
            details.put("plan_approvers", approverRules(plan, input.userId()));
            return DecisionTraceStep.of(ApiDecisionStepKind.ELIGIBLE_REVIEWERS, StepOutcome.ALLOW,
                    "apigov.simulation.reviewers.plan_approvers", details);
        }
        // No approver rules: the request is open to any API_REQUEST_REVIEW holder. Security rule —
        // a user can never approve their own call, whatever else they hold.
        var reviewerIds = rolePermissionHolderLookupService
                .findUserIdsWithPermission(connector.organizationId(), Permission.API_REQUEST_REVIEW)
                .stream()
                .filter(id -> !id.equals(input.userId()))
                .toList();
        details.put("reviewers", userQueryService.findByIds(reviewerIds).stream()
                .filter(UserView::active)
                .map(DefaultApiCallSimulationService::describeReviewer)
                .toList());
        boolean none = reviewerIds.isEmpty();
        return DecisionTraceStep.of(ApiDecisionStepKind.ELIGIBLE_REVIEWERS,
                none ? StepOutcome.DENY : StepOutcome.ALLOW,
                none ? "apigov.simulation.reviewers.none" : "apigov.simulation.reviewers.permission_holders",
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

    // ── 8. Response masking ───────────────────────────────────────────────────

    /**
     * Reported per <em>rule</em>, never per response field: the live masker walks the actual payload
     * by dot-path, and a simulation has no payload. Hence the mandatory {@code RESPONSE_SHAPE_ABSENT}
     * caveat — the step says which rules resolve, not which values would change.
     */
    private DecisionTraceStep maskingStep(UUID organizationId, ApiCallSimulationInput input,
                                          ResolvedApiConnectorPermission permission) {
        var masks = maskingResolutionService.resolveApplicable(organizationId, input.connectorId(),
                input.userId());
        var details = new LinkedHashMap<String, Object>();
        details.put("masks", masks.stream().map(DefaultApiCallSimulationService::describeMask).toList());
        details.put("restricted_response_field_count",
                permission == null ? 0 : permission.restrictedResponseFields().size());
        return DecisionTraceStep.of(ApiDecisionStepKind.RESPONSE_MASKING,
                masks.isEmpty() ? StepOutcome.NO_MATCH : StepOutcome.MATCH,
                masks.isEmpty() ? "apigov.simulation.masking.none" : "apigov.simulation.masking.resolved",
                details);
    }

    private static Map<String, Object> describeMask(ResolvedApiMask mask) {
        var entry = new LinkedHashMap<String, Object>();
        // A null policy_id marks a legacy restricted_response_fields entry rather than an AF-518
        // policy; the wire omits the key, so absent reads as "not from a policy".
        entry.put("policy_id", mask.policyId());
        entry.put("matcher_type", mask.matcherType().name());
        entry.put("field_ref", mask.fieldRef());
        entry.put("strategy", mask.strategy().name());
        entry.put("operation_id", mask.operationId());
        return entry;
    }

    // ── 9. Break-glass ────────────────────────────────────────────────────────

    private static DecisionTraceStep breakGlassStep(ResolvedApiConnectorPermission permission) {
        boolean eligible = permission != null && permission.canBreakGlass();
        var details = new LinkedHashMap<String, Object>();
        details.put("can_break_glass", eligible);
        return DecisionTraceStep.of(ApiDecisionStepKind.BREAK_GLASS,
                eligible ? StepOutcome.ALLOW : StepOutcome.DENY,
                eligible ? "apigov.simulation.break_glass.eligible"
                         : "apigov.simulation.break_glass.not_eligible",
                details);
    }

    // ── Assembly ──────────────────────────────────────────────────────────────

    /**
     * A call blocked before the decision chain still reports every stage, so a client renders one
     * stable checklist rather than a list whose length encodes how far the call got.
     */
    private static ApiCallSimulationResult blocked(List<DecisionTraceStep> steps,
                                                   Set<SimulationCaveat> caveats) {
        var reached = new LinkedHashMap<Object, DecisionTraceStep>();
        steps.forEach(step -> reached.putIfAbsent(step.step(), step));
        var full = new ArrayList<DecisionTraceStep>(ApiDecisionStepKind.values().length);
        for (var kind : ApiDecisionStepKind.values()) {
            var step = reached.get(kind);
            full.add(step != null ? step
                    : DecisionTraceStep.of(kind, StepOutcome.SKIP, "apigov.simulation.not_reached"));
        }
        return new ApiCallSimulationResult(full, null, List.copyOf(caveats));
    }
}
