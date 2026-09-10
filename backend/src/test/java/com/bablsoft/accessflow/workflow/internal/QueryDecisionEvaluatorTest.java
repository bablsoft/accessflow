package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.access.api.AccessGrantLookupService;
import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessGrantView;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.QueryEstimateLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.workflow.api.QueryDecisionStepKind;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.workflow.internal.routing.ConditionContextFactory;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingMatch;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingPolicyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The decision chain in isolation. {@link QueryReviewStateMachineTest} covers the same branches
 * through the listener and asserts the side effects; this one asserts the decision and the trace,
 * which is what the access simulator consumes.
 */
@ExtendWith(MockitoExtension.class)
class QueryDecisionEvaluatorTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock ReviewPlanLookupService reviewPlanLookupService;
    @Mock SqlParserService sqlParserService;
    @Mock UserQueryService userQueryService;
    @Mock UserGroupService userGroupService;
    @Mock RoutingPolicyEngine routingPolicyEngine;
    @Mock BehaviorAnomalyLookupService behaviorAnomalyLookupService;
    @Mock AccessGrantLookupService accessGrantLookupService;
    @Mock QueryEstimateLookupService queryEstimateLookupService;

    private QueryDecisionEvaluator evaluator;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T11:00:00Z"), ZoneOffset.UTC);
    private final UUID queryId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final UUID grantId = UUID.randomUUID();

    @BeforeEach
    void buildEvaluator() {
        var contextFactory = new ConditionContextFactory(queryRequestLookupService,
                sqlParserService, userQueryService, userGroupService, behaviorAnomalyLookupService,
                queryEstimateLookupService);
        evaluator = new QueryDecisionEvaluator(reviewPlanLookupService, contextFactory,
                sqlParserService, routingPolicyEngine, accessGrantLookupService);
    }

    @BeforeEach
    void stubSignals() {
        lenient().when(sqlParserService.parse(any()))
                .thenReturn(new SqlParseResult(QueryType.SELECT, "SELECT 1"));
    }

    // ── AI-failure path ───────────────────────────────────────────────────────

    @Test
    void aiFailureGoesStraightToReviewWithoutConsultingAnything() {
        var decision = evaluator.evaluate(query(QueryType.DELETE), AiOutcome.FAILED, null, -1, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.AI_FAILED_PENDING_REVIEW);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(decision.context()).isNull();
        verify(routingPolicyEngine, never()).evaluate(any(), any(), any());
        verify(accessGrantLookupService, never()).findActivePreApprovedGrants(any(), any(), any());
        verify(reviewPlanLookupService, never()).findForDatasource(any());
    }

    @Test
    void aiFailureTraceSkipsEveryDecisionStage() {
        var trace = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.FAILED, null, -1, clock)
                .trace();

        assertThat(trace.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(trace.steps()).extracting("step", "outcome").containsExactly(
                org.assertj.core.groups.Tuple.tuple(QueryDecisionStepKind.ROUTING_POLICIES,
                        StepOutcome.SKIP),
                org.assertj.core.groups.Tuple.tuple(QueryDecisionStepKind.GRANT_FAST_PATH,
                        StepOutcome.SKIP),
                org.assertj.core.groups.Tuple.tuple(QueryDecisionStepKind.REVIEW_PLAN,
                        StepOutcome.SKIP));
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    @Test
    void autoApprovePolicyApproves() {
        givenPlan(false, true);
        givenPolicyMatch(RoutingAction.AUTO_APPROVE, null);

        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.COMPLETED,
                RiskLevel.LOW, 10, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.ROUTING_AUTO_APPROVE);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(decision.effectiveApprovals()).isNull();
        assertThat(decision.routingMatch().policyId()).isEqualTo(policyId);
    }

    @Test
    void autoRejectPolicyRejects() {
        givenPlan(false, true);
        givenPolicyMatch(RoutingAction.AUTO_REJECT, null);

        var decision = evaluator.evaluate(query(QueryType.DELETE), AiOutcome.COMPLETED,
                RiskLevel.CRITICAL, 95, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.ROUTING_AUTO_REJECT);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.REJECTED);
    }

    @Test
    void requireApprovalsUsesThePolicyCountVerbatim() {
        givenPlan(false, true);
        givenPolicyMatch(RoutingAction.REQUIRE_APPROVALS, 3);

        var decision = evaluator.evaluate(query(QueryType.UPDATE), AiOutcome.COMPLETED,
                RiskLevel.MEDIUM, 40, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.ROUTING_REQUIRE_APPROVALS);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(decision.effectiveApprovals()).isEqualTo(3);
    }

    @Test
    void requireApprovalsWithoutACountDefaultsToOne() {
        givenPlan(false, true);
        givenPolicyMatch(RoutingAction.REQUIRE_APPROVALS, null);

        var decision = evaluator.evaluate(query(QueryType.UPDATE), AiOutcome.COMPLETED,
                RiskLevel.MEDIUM, 40, clock);

        assertThat(decision.effectiveApprovals()).isEqualTo(1);
    }

    @Test
    void escalateAddsItsDeltaToThePlanBasis() {
        givenPlan(false, true);
        givenPolicyMatch(RoutingAction.ESCALATE, 2);

        var decision = evaluator.evaluate(query(QueryType.UPDATE), AiOutcome.COMPLETED,
                RiskLevel.HIGH, 80, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.ROUTING_ESCALATE);
        assertThat(decision.effectiveApprovals()).isEqualTo(3);
    }

    @Test
    void escalateWithoutAPlanFallsBackToABasisOfOne() {
        when(reviewPlanLookupService.findForDatasource(eq(datasourceId)))
                .thenReturn(Optional.empty());
        givenPolicyMatch(RoutingAction.ESCALATE, 2);

        var decision = evaluator.evaluate(query(QueryType.UPDATE), AiOutcome.COMPLETED,
                RiskLevel.HIGH, 80, clock);

        assertThat(decision.effectiveApprovals()).isEqualTo(3);
    }

    @Test
    void aMatchedPolicyPreemptsTheGrantFastPathAndThePlan() {
        givenPlan(false, true);
        givenPolicyMatch(RoutingAction.ESCALATE, 1);

        var trace = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.COMPLETED, RiskLevel.LOW,
                5, clock).trace();

        assertThat(step(trace, QueryDecisionStepKind.ROUTING_POLICIES).outcome())
                .isEqualTo(StepOutcome.MATCH);
        assertThat(step(trace, QueryDecisionStepKind.ROUTING_POLICIES).details())
                .containsEntry("matched_policy_id", policyId)
                .containsEntry("action", "ESCALATE")
                .containsEntry("effective_min_approvals", 2);
        assertThat(step(trace, QueryDecisionStepKind.GRANT_FAST_PATH).outcome())
                .isEqualTo(StepOutcome.SKIP);
        assertThat(step(trace, QueryDecisionStepKind.REVIEW_PLAN).outcome()).isEqualTo(StepOutcome.SKIP);
        verify(accessGrantLookupService, never()).findActivePreApprovedGrants(any(), any(), any());
    }

    // ── Grant fast path (#582) ────────────────────────────────────────────────

    @Test
    void anActiveCoveringGrantApproves() {
        givenPlan(false, true);
        givenNoPolicyMatch();
        givenActiveGrant(grant(true, false, false, List.of(), List.of("orders")));
        when(sqlParserService.parse(any()))
                .thenReturn(new SqlParseResult(QueryType.SELECT, false, List.of("SELECT 1"),
                        Set.of("orders"), true, false));

        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.COMPLETED,
                RiskLevel.LOW, 5, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.GRANT_FAST_PATH);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(decision.grantId()).isEqualTo(grantId);
        assertThat(decision.grantApproverEmail()).isEqualTo("approver@x.io");
    }

    @Test
    void theSkippedPathStillAllowsTheGrantFastPath() {
        givenPlan(false, true);
        givenNoPolicyMatch();
        givenActiveGrant(grant(true, false, false, List.of(), List.of()));

        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.SKIPPED, null, -1,
                clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.GRANT_FAST_PATH);
    }

    @Test
    void highRiskSuppressesTheGrantFastPath() {
        givenPlan(false, true);
        givenNoPolicyMatch();

        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.COMPLETED,
                RiskLevel.HIGH, 80, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_PENDING_REVIEW);
        assertThat(step(decision.trace(), QueryDecisionStepKind.GRANT_FAST_PATH).reasonKey())
                .isEqualTo("workflow.decision.grant.suppressed_risk");
        verify(accessGrantLookupService, never()).findActivePreApprovedGrants(any(), any(), any());
    }

    @Test
    void anOpenAnomalySuppressesTheGrantFastPath() {
        givenPlan(false, true);
        givenNoPolicyMatch();
        when(behaviorAnomalyLookupService.hasActiveAnomaly(organizationId, submitterId,
                datasourceId)).thenReturn(true);

        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.COMPLETED,
                RiskLevel.LOW, 5, clock);

        assertThat(step(decision.trace(), QueryDecisionStepKind.GRANT_FAST_PATH).reasonKey())
                .isEqualTo("workflow.decision.grant.suppressed_anomaly");
        verify(accessGrantLookupService, never()).findActivePreApprovedGrants(any(), any(), any());
    }

    @Test
    void noActiveGrantFallsThroughToThePlan() {
        givenPlan(false, true);
        givenNoPolicyMatch();
        when(accessGrantLookupService.findActivePreApprovedGrants(organizationId, submitterId,
                datasourceId)).thenReturn(List.of());

        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.COMPLETED,
                RiskLevel.LOW, 5, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_PENDING_REVIEW);
        assertThat(step(decision.trace(), QueryDecisionStepKind.GRANT_FAST_PATH).reasonKey())
                .isEqualTo("workflow.decision.grant.none_active");
    }

    @Test
    void aGrantThatLacksTheCapabilityDoesNotCover() {
        givenPlan(false, true);
        givenNoPolicyMatch();
        givenActiveGrant(grant(true, false, false, List.of(), List.of()));

        var decision = evaluator.evaluate(query(QueryType.DELETE), AiOutcome.COMPLETED,
                RiskLevel.LOW, 5, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_PENDING_REVIEW);
        assertThat(step(decision.trace(), QueryDecisionStepKind.GRANT_FAST_PATH).reasonKey())
                .isEqualTo("workflow.decision.grant.no_covering_grant");
    }

    @Test
    void aGrantThatDoesNotCoverTheReferencedTablesDoesNotCover() {
        givenPlan(false, true);
        givenNoPolicyMatch();
        givenActiveGrant(grant(true, false, false, List.of(), List.of("orders")));
        when(sqlParserService.parse(any()))
                .thenReturn(new SqlParseResult(QueryType.SELECT, false, List.of("SELECT 1"),
                        Set.of("payments"), true, false));

        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.COMPLETED,
                RiskLevel.LOW, 5, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_PENDING_REVIEW);
    }

    @Test
    void aReparseFailureFailsTheGrantFastPathClosed() {
        givenPlan(false, true);
        givenNoPolicyMatch();
        givenActiveGrant(grant(true, true, true, List.of(), List.of()));
        // The context builder degrades to an empty table set on a parse failure, which would satisfy
        // any allow-list vacuously — so the fast path re-parses and refuses rather than inheriting it.
        when(sqlParserService.parse(any())).thenThrow(new IllegalStateException("unparseable"));

        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.COMPLETED,
                RiskLevel.LOW, 5, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_PENDING_REVIEW);
        assertThat(step(decision.trace(), QueryDecisionStepKind.GRANT_FAST_PATH).reasonKey())
                .isEqualTo("workflow.decision.grant.parse_failed");
    }

    // ── Review plan ───────────────────────────────────────────────────────────

    @Test
    void aPlanThatNeedsNoHumanApprovalApproves() {
        givenPlan(false, false);
        givenNoPolicyMatch();

        var decision = evaluator.evaluate(query(QueryType.UPDATE), AiOutcome.COMPLETED,
                RiskLevel.HIGH, 80, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_APPROVED);
        assertThat(step(decision.trace(), QueryDecisionStepKind.REVIEW_PLAN).reasonKey())
                .isEqualTo("workflow.decision.plan.no_human_approval");
    }

    @Test
    void autoApproveReadsApprovesALowRiskSelect() {
        givenPlan(true, true);
        givenNoPolicyMatch();
        givenNoGrants();

        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.COMPLETED,
                RiskLevel.LOW, 5, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_APPROVED);
        assertThat(step(decision.trace(), QueryDecisionStepKind.REVIEW_PLAN).reasonKey())
                .isEqualTo("workflow.decision.plan.auto_approve_reads");
    }

    @Test
    void autoApproveReadsDoesNotApproveAHighRiskSelect() {
        givenPlan(true, true);
        givenNoPolicyMatch();

        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.COMPLETED,
                RiskLevel.HIGH, 80, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_PENDING_REVIEW);
    }

    @Test
    void autoApproveReadsCannotFireWithoutAnAiRiskSignal() {
        givenPlan(true, true);
        givenNoPolicyMatch();
        givenNoGrants();

        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.SKIPPED, null, -1,
                clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_PENDING_REVIEW);
    }

    @Test
    void aSimulatedRiskVerdictIsIgnoredOnTheSkippedPath() {
        givenPlan(true, true);
        givenNoPolicyMatch();
        givenNoGrants();

        // SKIPPED means the datasource has AI analysis off; production never sees a verdict there,
        // so a caller-supplied one must not resurrect the auto-approve-reads fast path.
        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.SKIPPED, RiskLevel.LOW,
                5, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_PENDING_REVIEW);
        assertThat(decision.context().riskLevel()).isNull();
        assertThat(decision.context().riskScore()).isEqualTo(-1);
    }

    @Test
    void noPlanGoesToHumanReview() {
        when(reviewPlanLookupService.findForDatasource(eq(datasourceId)))
                .thenReturn(Optional.empty());
        givenNoPolicyMatch();
        givenNoGrants();

        var decision = evaluator.evaluate(query(QueryType.SELECT), AiOutcome.COMPLETED,
                RiskLevel.LOW, 5, clock);

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_PENDING_REVIEW);
        var planStep = step(decision.trace(), QueryDecisionStepKind.REVIEW_PLAN);
        assertThat(planStep.reasonKey()).isEqualTo("workflow.decision.plan.absent");
        assertThat(planStep.details()).containsEntry("review_plan_id", null);
    }

    @Test
    void thePlanFallThroughTraceCoversAllThreeStages() {
        givenPlan(false, true);
        givenNoPolicyMatch();
        givenNoGrants();

        var trace = evaluator.evaluate(query(QueryType.UPDATE), AiOutcome.COMPLETED, RiskLevel.LOW,
                5, clock).trace();

        assertThat(trace.steps()).extracting("step").containsExactly(
                QueryDecisionStepKind.ROUTING_POLICIES, QueryDecisionStepKind.GRANT_FAST_PATH,
                QueryDecisionStepKind.REVIEW_PLAN);
        assertThat(step(trace, QueryDecisionStepKind.ROUTING_POLICIES).outcome())
                .isEqualTo(StepOutcome.NO_MATCH);
        assertThat(step(trace, QueryDecisionStepKind.REVIEW_PLAN).outcome()).isEqualTo(StepOutcome.DENY);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static com.bablsoft.accessflow.workflow.api.DecisionTraceStep step(
            com.bablsoft.accessflow.workflow.api.DecisionTrace trace, QueryDecisionStepKind kind) {
        return trace.steps().stream().filter(s -> s.step() == kind).findFirst().orElseThrow();
    }

    private QueryRequestSnapshot query(QueryType type) {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", type, false, QueryStatus.PENDING_AI, null, "203.0.113.7", "curl/8.4.0",
                true);
    }

    private void givenPlan(boolean autoApproveReads, boolean requiresHumanApproval) {
        when(reviewPlanLookupService.findForDatasource(eq(datasourceId)))
                .thenReturn(Optional.of(new ReviewPlanSnapshot(UUID.randomUUID(), organizationId,
                        true, requiresHumanApproval, 1, autoApproveReads, 1,
                        List.of(new ApproverRule(null, "REVIEWER", 1)), List.of())));
    }

    private void givenPolicyMatch(RoutingAction action, Integer requiredApprovals) {
        when(routingPolicyEngine.evaluate(eq(organizationId), eq(datasourceId), any()))
                .thenReturn(Optional.of(new RoutingMatch(policyId, "P", action, requiredApprovals,
                        "matched")));
    }

    private void givenNoPolicyMatch() {
        when(routingPolicyEngine.evaluate(eq(organizationId), eq(datasourceId), any()))
                .thenReturn(Optional.empty());
    }

    private void givenNoGrants() {
        when(accessGrantLookupService.findActivePreApprovedGrants(organizationId, submitterId,
                datasourceId)).thenReturn(List.of());
    }

    private void givenActiveGrant(AccessGrantView grant) {
        when(accessGrantLookupService.findActivePreApprovedGrants(organizationId, submitterId,
                datasourceId)).thenReturn(List.of(grant));
    }

    private AccessGrantView grant(boolean canRead, boolean canWrite, boolean canDdl,
                                  List<String> allowedSchemas, List<String> allowedTables) {
        return new AccessGrantView(grantId, organizationId, submitterId, datasourceId, canRead,
                canWrite, canDdl, allowedSchemas, allowedTables, AccessGrantStatus.APPROVED,
                Instant.now().plusSeconds(3600), UUID.randomUUID(), "approver@x.io", Instant.now());
    }
}
