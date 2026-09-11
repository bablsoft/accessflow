package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.ColumnRefKeys;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionContribution;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.QuotaExceededException;
import com.bablsoft.accessflow.core.api.QuotaService;
import com.bablsoft.accessflow.core.api.ResolvedRowSecurityPredicate;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.SimulationCaveat;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.proxy.api.RowSecurityClassificationService;
import com.bablsoft.accessflow.workflow.api.AccessSimulationInput;
import com.bablsoft.accessflow.workflow.api.AccessSimulationResult;
import com.bablsoft.accessflow.workflow.api.AccessSimulationService;
import com.bablsoft.accessflow.workflow.api.BreakGlassEligibilityService;
import com.bablsoft.accessflow.workflow.api.QueryDecisionStepKind;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingPolicyEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Traces a hypothetical request through the real evaluators (issue AF-859).
 *
 * <p>Stages 5–7 come straight from {@link QueryDecisionEvaluator}, the same object the live listener
 * uses, so the routing / grant / review-plan verdicts here are not a model of production — they are
 * production. The stages around them are reconstructed: 1–4 are the synchronous submission gate,
 * which has already run by the time the live decision path starts, and 8–11 belong to execution.
 *
 * <p><strong>Read-only by construction.</strong> Note what is absent from the constructor: no
 * persistence service, no state service, no event publisher, no AI analyzer, no notification
 * dispatcher, and nothing that opens a connection to a customer database. That is the guarantee, and
 * {@code DefaultAccessSimulationServiceTest} asserts it against the declared fields so it cannot be
 * weakened by accident.
 */
@Service
@RequiredArgsConstructor
class DefaultAccessSimulationService implements AccessSimulationService {

    private final DatasourceAdminService datasourceAdminService;
    private final UserQueryService userQueryService;
    private final QuotaService quotaService;
    private final QueryParser queryParser;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final RolePermissionHolderLookupService rolePermissionHolderLookupService;
    private final QueryDecisionEvaluator queryDecisionEvaluator;
    private final RoutingPolicyEngine routingPolicyEngine;
    private final ReviewPlanLookupService reviewPlanLookupService;
    private final ReviewerEligibilityService reviewerEligibilityService;
    private final RowSecurityResolutionService rowSecurityResolutionService;
    private final RowSecurityClassificationService rowSecurityClassificationService;
    private final MaskingPolicyResolutionService maskingPolicyResolutionService;
    private final BreakGlassEligibilityService breakGlassEligibilityService;

    // Time-of-day / day-of-week routing conditions evaluate in the server's local zone, so the
    // simulator has to use the same zone the live listener does. Deliberately NOT the injected
    // Clock bean, which is UTC: tracing those conditions against a different wall clock than
    // production evaluates them on would report the wrong winner for exactly the class of policy
    // this endpoint exists to debug.
    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    // Deliberately not @Transactional: this is an aggregation of a dozen independently
    // transactional reads with no consistency requirement between them, and wrapping it would let a
    // single handled DatasourceNotFoundException from an inner transactional call mark the whole
    // read rollback-only.
    @Override
    public AccessSimulationResult simulate(UUID organizationId, AccessSimulationInput input) {
        var user = userQueryService.findById(input.userId())
                .filter(u -> organizationId.equals(u.organizationId()))
                .orElseThrow(() -> new UserNotFoundException(input.userId()));
        // getForAdmin scopes to the caller's org and 404s outside it; per-user visibility is a
        // separate question, answered below against the production predicate rather than a copy.
        var datasource = datasourceAdminService.getForAdmin(input.datasourceId(), organizationId);

        var steps = new ArrayList<DecisionTraceStep>(QueryDecisionStepKind.values().length);
        var caveats = EnumSet.noneOf(SimulationCaveat.class);
        caveats.add(SimulationCaveat.CLIENT_CONTEXT_ABSENT);
        caveats.add(SimulationCaveat.COST_ESTIMATE_ABSENT);

        boolean visible = datasourceAdminService.isVisibleToUser(input.datasourceId(),
                organizationId, input.userId());
        steps.add(datasourceStep(datasource, visible));
        steps.add(quotaStep(organizationId));
        var parsed = parse(datasource, input.sql(), steps);
        if (parsed == null) {
            return blocked(steps, caveats);
        }
        boolean permitted = permissionStep(organizationId, input, parsed, steps);
        if (!visible || !datasource.active() || !permitted || quotaExceeded(steps)) {
            return blocked(steps, caveats);
        }

        var decision = queryDecisionEvaluator.evaluate(
                syntheticSnapshot(organizationId, input, parsed), input.aiOutcome(),
                input.riskLevel(), input.effectiveRiskScore(), clock);
        steps.addAll(withFullPolicyList(decision, organizationId, input.datasourceId()));

        steps.add(reviewerStep(input, decision.nextStatus()));
        steps.add(rowSecurityStep(organizationId, input, datasource, caveats));
        steps.add(maskingStep(organizationId, input, parsed, caveats));
        steps.add(breakGlassStep(user, input));
        return new AccessSimulationResult(steps, decision.nextStatus(), decision.context(),
                List.copyOf(caveats));
    }

    // ── 1. Datasource gates ───────────────────────────────────────────────────

    private static DecisionTraceStep datasourceStep(DatasourceView datasource, boolean visible) {
        var details = new LinkedHashMap<String, Object>();
        details.put("db_type", datasource.dbType().name());
        details.put("active", datasource.active());
        details.put("ai_analysis_enabled", datasource.aiAnalysisEnabled());
        details.put("visible_to_user", visible);
        String reasonKey;
        StepOutcome outcome;
        if (!visible) {
            outcome = StepOutcome.DENY;
            reasonKey = "workflow.access_simulation.datasource.not_visible";
        } else if (!datasource.active()) {
            outcome = StepOutcome.DENY;
            reasonKey = "workflow.access_simulation.datasource.inactive";
        } else {
            outcome = StepOutcome.ALLOW;
            reasonKey = "workflow.access_simulation.datasource.allowed";
        }
        return DecisionTraceStep.of(QueryDecisionStepKind.DATASOURCE_GATES, outcome, reasonKey, details);
    }

    // ── 2. Quota ──────────────────────────────────────────────────────────────

    private DecisionTraceStep quotaStep(UUID organizationId) {
        try {
            quotaService.checkQueryQuota(organizationId);
            return DecisionTraceStep.of(QueryDecisionStepKind.QUOTA, StepOutcome.ALLOW,
                    "workflow.access_simulation.quota.within_limit");
        } catch (QuotaExceededException ex) {
            var details = new LinkedHashMap<String, Object>();
            details.put("quota_type", ex.quotaType().name());
            details.put("limit", ex.limit());
            details.put("current", ex.current());
            return DecisionTraceStep.of(QueryDecisionStepKind.QUOTA, StepOutcome.DENY,
                    "workflow.access_simulation.quota.exceeded", details);
        }
    }

    private static boolean quotaExceeded(List<DecisionTraceStep> steps) {
        return steps.stream().anyMatch(s -> s.step() == QueryDecisionStepKind.QUOTA
                && s.outcome() == StepOutcome.DENY);
    }

    // ── 3. Parse ──────────────────────────────────────────────────────────────

    /** @return the parse result, or {@code null} when the submission would be rejected with 422. */
    private SqlParseResult parse(DatasourceView datasource, String sql,
                                 List<DecisionTraceStep> steps) {
        SqlParseResult parsed;
        try {
            parsed = queryParser.parse(sql, datasource.dbType());
        } catch (InvalidSqlException ex) {
            steps.add(DecisionTraceStep.of(QueryDecisionStepKind.SQL_PARSE, StepOutcome.DENY,
                    "workflow.access_simulation.parse.unparseable"));
            return null;
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("query_type", parsed.type().name());
        details.put("referenced_tables", List.copyOf(parsed.referencedTables()));
        details.put("transactional", parsed.transactional());
        details.put("has_where_clause", parsed.hasWhereClause());
        details.put("has_limit_clause", parsed.hasLimitClause());
        if (parsed.type() == QueryType.OTHER) {
            steps.add(DecisionTraceStep.of(QueryDecisionStepKind.SQL_PARSE, StepOutcome.DENY,
                    "workflow.access_simulation.parse.unsupported_type", details));
            return null;
        }
        steps.add(DecisionTraceStep.of(QueryDecisionStepKind.SQL_PARSE, StepOutcome.ALLOW,
                "workflow.access_simulation.parse.ok", details));
        return parsed;
    }

    // ── 4. Effective permission ───────────────────────────────────────────────

    private boolean permissionStep(UUID organizationId, AccessSimulationInput input,
                                   SqlParseResult parsed, List<DecisionTraceStep> steps) {
        var details = new LinkedHashMap<String, Object>();
        boolean queryAdmin = rolePermissionHolderLookupService
                .findUserIdsWithPermission(organizationId, Permission.QUERY_ADMIN)
                .contains(input.userId());
        details.put("query_admin_short_circuit", queryAdmin);

        var contributions = permissionLookupService.findContributions(input.userId(),
                input.datasourceId());
        details.put("contributing_grants", contributions.stream()
                .map(DefaultAccessSimulationService::describeContribution)
                .toList());

        if (queryAdmin) {
            // QUERY_ADMIN holders skip the per-datasource gate outright, so they pass here with no
            // permission row at all. Saying so is the point: it is invisible on every other screen.
            details.put("rejected_tables", List.of());
            details.put("expires_at", null);
            steps.add(DecisionTraceStep.of(QueryDecisionStepKind.EFFECTIVE_PERMISSION, StepOutcome.ALLOW,
                    "workflow.access_simulation.permission.query_admin_bypass", details));
            return true;
        }

        var permission = permissionLookupService.findFor(input.userId(), input.datasourceId())
                .orElse(null);
        if (permission == null) {
            details.put("rejected_tables", List.of());
            details.put("expires_at", null);
            steps.add(DecisionTraceStep.of(QueryDecisionStepKind.EFFECTIVE_PERMISSION, StepOutcome.DENY,
                    "workflow.access_simulation.permission.none", details));
            return false;
        }
        details.put("expires_at", permission.expiresAt());
        boolean capable = DatasourcePermissionChecker.hasCapability(permission, parsed.type());
        var rejected = DatasourcePermissionChecker.rejectedTables(permission,
                parsed.referencedTables());
        details.put("rejected_tables", List.copyOf(rejected));
        if (!capable) {
            steps.add(DecisionTraceStep.of(QueryDecisionStepKind.EFFECTIVE_PERMISSION, StepOutcome.DENY,
                    "workflow.access_simulation.permission.capability_missing", details));
            return false;
        }
        if (!rejected.isEmpty()) {
            steps.add(DecisionTraceStep.of(QueryDecisionStepKind.EFFECTIVE_PERMISSION, StepOutcome.DENY,
                    "workflow.access_simulation.permission.table_not_allowed", details));
            return false;
        }
        // An allow-list check over an empty table set passes vacuously — the enforcement gate has the
        // same shape. Flagging it stops the trace from reading as a positive verdict it is not.
        var reasonKey = parsed.referencedTables().isEmpty()
                ? "workflow.access_simulation.permission.allowed_no_tables"
                : "workflow.access_simulation.permission.allowed";
        steps.add(DecisionTraceStep.of(QueryDecisionStepKind.EFFECTIVE_PERMISSION, StepOutcome.ALLOW,
                reasonKey, details));
        return true;
    }

    private static Map<String, Object> describeContribution(DatasourcePermissionContribution c) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("source_kind", c.sourceKind().name());
        entry.put("source_id", c.sourceId());
        entry.put("group_id", c.groupId());
        entry.put("group_name", c.groupName());
        entry.put("expires_at", c.expiresAt());
        return entry;
    }

    // ── 5–7. The live decision chain, with the full policy list attached ──────

    /**
     * The evaluator's own steps, with the routing step enriched by every policy it considered.
     * The live path short-circuits at the first match and has no reason to pay for the rest; an
     * explainer does, because a policy that nearly matched is the most useful line in the trace.
     */
    private List<DecisionTraceStep> withFullPolicyList(QueryDecision decision, UUID organizationId,
                                                       UUID datasourceId) {
        var context = decision.context();
        if (context == null) {
            return decision.trace().steps();
        }
        var evaluations = routingPolicyEngine.evaluateAll(
                routingPolicyEngine.enabledFor(organizationId, datasourceId), context);
        // "decisive" comes from the decision itself, not from this second evaluation's own first
        // match, so the presentational list can never name a different winner than the one that
        // decided.
        var decidedBy = decision.routingMatch() == null ? null : decision.routingMatch().policyId();
        var policies = evaluations.stream().map(e -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("policy_id", e.policy().id());
            entry.put("name", e.policy().name());
            entry.put("priority", e.policy().priority());
            entry.put("action", e.policy().action().name());
            entry.put("required_approvals", e.policy().requiredApprovals());
            entry.put("matched", e.matched());
            entry.put("decisive", e.policy().id() != null && e.policy().id().equals(decidedBy));
            return (Object) entry;
        }).toList();
        return decision.trace().steps().stream()
                .map(step -> step.step() == QueryDecisionStepKind.ROUTING_POLICIES
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

    // ── 8. Eligible reviewers ─────────────────────────────────────────────────

    private DecisionTraceStep reviewerStep(AccessSimulationInput input, QueryStatus resultingStatus) {
        if (resultingStatus != QueryStatus.PENDING_REVIEW) {
            return DecisionTraceStep.of(QueryDecisionStepKind.ELIGIBLE_REVIEWERS, StepOutcome.SKIP,
                    "workflow.access_simulation.reviewers.not_pending_review");
        }
        var eligible = reviewerEligibilityService.findEligibleReviewerIds(input.datasourceId())
                .orElse(null);
        var details = new LinkedHashMap<String, Object>();
        details.put("submitter_excluded", true);
        if (eligible == null) {
            // No per-datasource assignment: the plan's approver rules decide, and those are role
            // shapes rather than a resolvable user list. A rule naming the submitter is dropped
            // here too, so the flag above is true on this branch as well.
            details.put("plan_approvers", reviewPlanLookupService
                    .findForDatasource(input.datasourceId())
                    .map(plan -> plan.approvers().stream()
                            .filter(rule -> !input.userId().equals(rule.userId()))
                            .map(DefaultAccessSimulationService::describeApprover)
                            .toList())
                    .orElse(List.of()));
            return DecisionTraceStep.of(QueryDecisionStepKind.ELIGIBLE_REVIEWERS, StepOutcome.ALLOW,
                    "workflow.access_simulation.reviewers.plan_approvers", details);
        }
        // Security rule: a user can never approve their own query, whatever else they hold. Note
        // this is the datasource's reviewer *assignment*, not the full live eligibility test — the
        // decision path additionally requires QUERY_REVIEW and stage eligibility, and resolves
        // delegation. The reason key says "assigned" rather than "eligible" for that reason.
        var reviewerIds = eligible.stream().filter(id -> !id.equals(input.userId())).toList();
        details.put("reviewers", userQueryService.findByIds(reviewerIds).stream()
                .filter(UserView::active)
                .map(DefaultAccessSimulationService::describeReviewer)
                .toList());
        var outcome = reviewerIds.isEmpty() ? StepOutcome.DENY : StepOutcome.ALLOW;
        var reasonKey = reviewerIds.isEmpty()
                ? "workflow.access_simulation.reviewers.none"
                : "workflow.access_simulation.reviewers.assigned";
        return DecisionTraceStep.of(QueryDecisionStepKind.ELIGIBLE_REVIEWERS, outcome, reasonKey,
                details);
    }

    private static Map<String, Object> describeReviewer(UserView user) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("user_id", user.id());
        entry.put("email", user.email());
        entry.put("display_name", user.displayName());
        return entry;
    }

    private static Map<String, Object> describeApprover(ApproverRule rule) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("user_id", rule.userId());
        entry.put("role", rule.role());
        entry.put("stage", rule.stage());
        return entry;
    }

    // ── 9. Row security ───────────────────────────────────────────────────────

    private DecisionTraceStep rowSecurityStep(UUID organizationId, AccessSimulationInput input,
                                              DatasourceView datasource,
                                              Set<SimulationCaveat> caveats) {
        var predicates = rowSecurityResolutionService.resolveApplicable(organizationId,
                input.datasourceId(), input.userId());
        if (predicates.isEmpty()) {
            return DecisionTraceStep.of(QueryDecisionStepKind.ROW_SECURITY, StepOutcome.NO_MATCH,
                    "workflow.access_simulation.row_security.none");
        }
        var classification = rowSecurityClassificationService.classify(input.datasourceId(),
                datasource.dbType(), input.sql(), toDirectives(predicates));
        if (classification.outcome() == RowSecurityOutcome.UNKNOWN) {
            caveats.add(SimulationCaveat.ENGINE_CLASSIFICATION_UNAVAILABLE);
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("engine_id", classification.engineId());
        details.put("row_security_outcome", classification.outcome().name());
        details.put("applied_policy_ids", List.copyOf(classification.appliedPolicyIds()));
        details.put("predicates", predicates.stream()
                .map(DefaultAccessSimulationService::describePredicate)
                .toList());
        var outcome = switch (classification.outcome()) {
            case APPLIED, DENY_ALL -> StepOutcome.MATCH;
            case FAIL_CLOSED -> StepOutcome.DENY;
            case NOT_APPLICABLE -> StepOutcome.NO_MATCH;
            case UNKNOWN -> StepOutcome.SKIP;
        };
        return DecisionTraceStep.of(QueryDecisionStepKind.ROW_SECURITY, outcome,
                "workflow.access_simulation.row_security."
                        + classification.outcome().name().toLowerCase(Locale.ROOT),
                details);
    }

    private static Map<String, Object> describePredicate(ResolvedRowSecurityPredicate predicate) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("policy_id", predicate.policyId());
        entry.put("table_ref", predicate.tableRef());
        entry.put("column_name", predicate.columnName());
        entry.put("operator", predicate.operator().name());
        entry.put("value_count", predicate.values().size());
        return entry;
    }

    private static List<RowSecurityDirective> toDirectives(
            List<ResolvedRowSecurityPredicate> predicates) {
        return predicates.stream()
                .map(p -> new RowSecurityDirective(p.policyId(), p.tableRef(), p.columnName(),
                        p.operator(), p.values()))
                .toList();
    }

    // ── 10. Masking ───────────────────────────────────────────────────────────

    /**
     * Masking policies, not masked columns. The live resolver keys off the executing statement's
     * {@code ResultSetMetaData}, which a simulation has no access to — so this reports which policies
     * resolve for the referenced tables and says as much, rather than implying column-level fidelity
     * it cannot deliver.
     */
    private DecisionTraceStep maskingStep(UUID organizationId, AccessSimulationInput input,
                                          SqlParseResult parsed, Set<SimulationCaveat> caveats) {
        var applicable = maskingPolicyResolutionService.resolveApplicable(organizationId,
                input.datasourceId(), input.userId());
        var matching = new ArrayList<Object>();
        for (var mask : applicable) {
            var keys = ColumnRefKeys.parse(mask.columnRef());
            boolean bareName = keys.table() == null;
            if (bareName) {
                // A bare column name matches any table, so it cannot be excluded — and cannot be
                // confirmed either. Reported with the caveat rather than silently dropped.
                caveats.add(SimulationCaveat.COLUMN_MATCH_BARE_NAME);
            } else if (!referencesTable(parsed, keys)) {
                continue;
            }
            var entry = new LinkedHashMap<String, Object>();
            entry.put("policy_id", mask.policyId());
            entry.put("column_ref", mask.columnRef());
            entry.put("strategy", mask.strategy().name());
            entry.put("bare_column_name", bareName);
            matching.add(entry);
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("policies", matching);
        return DecisionTraceStep.of(QueryDecisionStepKind.MASKING,
                matching.isEmpty() ? StepOutcome.NO_MATCH : StepOutcome.MATCH,
                matching.isEmpty()
                        ? "workflow.access_simulation.masking.none"
                        : "workflow.access_simulation.masking.resolved",
                details);
    }

    /**
     * A {@code column_ref} covers a query when the table it names is one of the parsed tables.
     *
     * <p>A three-part ref carries its schema and is matched on the qualified name, mirroring the
     * live resolver's most-specific match — dropping the schema would report
     * {@code analytics.users.email} as covering a query against {@code public.users}.
     */
    private static boolean referencesTable(SqlParseResult parsed, ColumnRefKeys keys) {
        var qualified = keys.full() != null
                ? keys.full().substring(0, keys.full().lastIndexOf('.'))
                : null;
        var bare = keys.table().substring(0, keys.table().lastIndexOf('.'));
        for (var referenced : parsed.referencedTables()) {
            if (qualified != null) {
                if (referenced.equals(qualified)) {
                    return true;
                }
            } else if (referenced.equals(bare) || referenced.endsWith("." + bare)) {
                return true;
            }
        }
        return false;
    }

    // ── 11. Break-glass ───────────────────────────────────────────────────────

    private DecisionTraceStep breakGlassStep(UserView user, AccessSimulationInput input) {
        var eligibility = breakGlassEligibilityService
                .findEligible(input.userId(), user.organizationId()).stream()
                .filter(e -> e.datasourceId().equals(input.datasourceId()))
                .findFirst()
                .orElse(null);
        var details = new LinkedHashMap<String, Object>();
        details.put("can_break_glass", eligibility != null);
        details.put("expires_at", eligibility == null ? null : eligibility.expiresAt());
        return DecisionTraceStep.of(QueryDecisionStepKind.BREAK_GLASS,
                eligibility == null ? StepOutcome.DENY : StepOutcome.ALLOW,
                eligibility == null
                        ? "workflow.access_simulation.break_glass.not_eligible"
                        : "workflow.access_simulation.break_glass.eligible",
                details);
    }

    // ── Assembly ──────────────────────────────────────────────────────────────

    /**
     * A request blocked before the decision chain still reports every stage, so a client renders one
     * stable checklist rather than a list whose length encodes how far the request got.
     */
    private static AccessSimulationResult blocked(List<DecisionTraceStep> steps,
                                                  Set<SimulationCaveat> caveats) {
        // Built in enum order rather than sorted afterwards: DecisionTraceStep.step() is typed as
        // the cross-kind DecisionStepKind interface, which is deliberately not Comparable.
        var reached = steps.stream()
                .collect(Collectors.toMap(DecisionTraceStep::step, step -> step, (a, b) -> a,
                        LinkedHashMap::new));
        var full = new ArrayList<DecisionTraceStep>(QueryDecisionStepKind.values().length);
        for (var kind : QueryDecisionStepKind.values()) {
            var step = reached.get(kind);
            full.add(step != null ? step : DecisionTraceStep.of(kind, StepOutcome.SKIP,
                    "workflow.access_simulation.not_reached"));
        }
        return new AccessSimulationResult(full, null, null, List.copyOf(caveats));
    }

    /**
     * A request-shaped value for the evaluator, carrying a random id it will never look anything up
     * by: the cost estimate is keyed on a real query id and comes back empty, and the last-approval
     * lookup only excludes that id. Client context is null because a hypothetical request has none —
     * reported as {@code CLIENT_CONTEXT_ABSENT} rather than invented.
     */
    private static QueryRequestSnapshot syntheticSnapshot(
            UUID organizationId, AccessSimulationInput input, SqlParseResult parsed) {
        return new QueryRequestSnapshot(UUID.randomUUID(),
                input.datasourceId(), organizationId, input.userId(), input.sql(), parsed.type(),
                parsed.transactional(), QueryStatus.PENDING_AI, null, null, null, false);
    }
}
