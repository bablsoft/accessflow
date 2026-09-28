package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.access.api.AccessGrantLookupService;
import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessGrantView;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.DataBudgetStatus;
import com.bablsoft.accessflow.core.api.DataBudgetStatusService;
import com.bablsoft.accessflow.core.api.DecisionTrace;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryEstimateLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;
import com.bablsoft.accessflow.workflow.api.QueryDecisionStepKind;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.internal.hook.DecisionHookConsultation;
import com.bablsoft.accessflow.workflow.internal.hook.DecisionHookInvoker;
import com.bablsoft.accessflow.workflow.internal.routing.ConditionContextFactory;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingMatch;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingPolicyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The external decision hook's place in the decision chain (#945): consulted only when no policy
 * matched, before the grant fast path; able to reject or raise the approval count; failing closed to
 * human review; and — whatever it answers — never approving.
 */
@ExtendWith(MockitoExtension.class)
class QueryDecisionEvaluatorDecisionHookTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock ReviewPlanLookupService reviewPlanLookupService;
    @Mock SqlParserService sqlParserService;
    @Mock UserQueryService userQueryService;
    @Mock UserGroupService userGroupService;
    @Mock RoutingPolicyEngine routingPolicyEngine;
    @Mock BehaviorAnomalyLookupService behaviorAnomalyLookupService;
    @Mock AccessGrantLookupService accessGrantLookupService;
    @Mock QueryEstimateLookupService queryEstimateLookupService;
    @Mock DataBudgetStatusService dataBudgetStatusService;

    private QueryDecisionEvaluator evaluator;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-28T11:00:00Z"), ZoneOffset.UTC);
    private final UUID queryId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID hookId = UUID.randomUUID();

    @BeforeEach
    void buildEvaluator() {
        var contextFactory = new ConditionContextFactory(queryRequestLookupService,
                sqlParserService, userQueryService, userGroupService, behaviorAnomalyLookupService,
                queryEstimateLookupService, dataBudgetStatusService);
        lenient().when(dataBudgetStatusService.statusFor(any(), any()))
                .thenAnswer(inv -> DataBudgetStatus.none(inv.getArgument(0)));
        lenient().when(sqlParserService.parse(any()))
                .thenReturn(new SqlParseResult(QueryType.SELECT, "SELECT 1"));
        evaluator = new QueryDecisionEvaluator(reviewPlanLookupService, contextFactory,
                sqlParserService, routingPolicyEngine, accessGrantLookupService);
    }

    @Test
    void aHookThatRejectsRejectsAndSkipsTheGrantAndThePlan() {
        givenPlan(true, false, 1);
        givenNoPolicyMatch();

        var decision = evaluate(answering(DecisionHookOutcome.REJECT, null));

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.DECISION_HOOK_REJECT);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.REJECTED);
        assertThat(decision.hook().hookId()).isEqualTo(hookId);
        assertThat(step(decision.trace(), QueryDecisionStepKind.DECISION_HOOK).outcome())
                .isEqualTo(StepOutcome.DENY);
        assertThat(step(decision.trace(), QueryDecisionStepKind.GRANT_FAST_PATH).reasonKey())
                .isEqualTo("workflow.decision.grant.skipped_hook_decided");
        verify(accessGrantLookupService, never()).findActivePreApprovedGrants(any(), any(), any());
    }

    @Test
    void aHookThatEscalatesRaisesThePlanMinimumByTheDelta() {
        givenPlan(false, true, 2);
        givenNoPolicyMatch();

        var decision = evaluate(answering(DecisionHookOutcome.ESCALATE, 3));

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.DECISION_HOOK_ESCALATE);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(decision.effectiveApprovals()).isEqualTo(5);
        assertThat(step(decision.trace(), QueryDecisionStepKind.DECISION_HOOK).reasonArgs())
                .containsExactly("OPA", "5");
    }

    @Test
    void anEscalationTurnsAnAutoApprovingPlanIntoReview() {
        givenPlan(true, false, 1);
        givenNoPolicyMatch();

        var decision = evaluate(answering(DecisionHookOutcome.ESCALATE, 1));

        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(decision.effectiveApprovals()).isEqualTo(2);
    }

    @Test
    void requireApprovalsNeverLowersThePlanMinimum() {
        givenPlan(false, true, 3);
        givenNoPolicyMatch();

        var decision = evaluate(answering(DecisionHookOutcome.REQUIRE_APPROVALS, 1));

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.DECISION_HOOK_REQUIRE_APPROVALS);
        assertThat(decision.effectiveApprovals()).isEqualTo(3);
    }

    @Test
    void requireApprovalsAboveThePlanMinimumIsHonoured() {
        givenPlan(false, true, 1);
        givenNoPolicyMatch();

        var decision = evaluate(answering(DecisionHookOutcome.REQUIRE_APPROVALS, 4));

        assertThat(decision.effectiveApprovals()).isEqualTo(4);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void aHookThatAllowsLeavesThePlanToDecide() {
        givenPlan(true, false, 1);
        givenNoPolicyMatch();
        givenNoGrants();

        var decision = evaluate(answering(DecisionHookOutcome.ALLOW, null));

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_APPROVED);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(decision.hook().outcome()).isEqualTo(DecisionHookOutcome.ALLOW);
        assertThat(step(decision.trace(), QueryDecisionStepKind.DECISION_HOOK).outcome())
                .isEqualTo(StepOutcome.ALLOW);
    }

    @Test
    void aHookThatAllowsLeavesTheGrantFastPathToDecide() {
        givenPlan(false, true, 1);
        givenNoPolicyMatch();
        givenActiveGrant();

        var decision = evaluate(answering(DecisionHookOutcome.ALLOW, null));

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.GRANT_FAST_PATH);
        assertThat(decision.hook()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(DecisionHookFailure.class)
    void everyFailureSendsAnAutoApprovingPlanToHumanReview(DecisionHookFailure failure) {
        givenPlan(true, false, 1);
        givenNoPolicyMatch();
        givenNoGrants();

        var decision = evaluate(failing(failure));

        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.PLAN_PENDING_REVIEW);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(decision.effectiveApprovals()).isNull();
        var plan = step(decision.trace(), QueryDecisionStepKind.REVIEW_PLAN);
        assertThat(plan.reasonKey()).isEqualTo("workflow.decision.plan.suppressed_decision_hook");
        assertThat(plan.details()).containsEntry("decision_hook_suppressed", true);
        var hook = step(decision.trace(), QueryDecisionStepKind.DECISION_HOOK);
        assertThat(hook.outcome()).isEqualTo(StepOutcome.MATCH);
        assertThat(hook.reasonArgs()).containsExactly("OPA", failure.name());
    }

    @Test
    void aFailureSuppressesAGrantThatWouldHaveApproved() {
        givenPlan(false, true, 1);
        givenNoPolicyMatch();
        givenActiveGrant();

        var decision = evaluate(failing(DecisionHookFailure.TIMEOUT));

        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(step(decision.trace(), QueryDecisionStepKind.GRANT_FAST_PATH).reasonKey())
                .isEqualTo("workflow.decision.grant.suppressed_decision_hook");
    }

    @ParameterizedTest
    @EnumSource(DecisionHookOutcome.class)
    void noHookAnswerEverApprovesAQueryThePlanWouldReview(DecisionHookOutcome outcome) {
        givenPlan(false, true, 1);
        givenNoPolicyMatch();
        lenient().when(accessGrantLookupService.findActivePreApprovedGrants(organizationId,
                submitterId, datasourceId)).thenReturn(List.of());

        var consultation = outcome == DecisionHookOutcome.FAILED
                ? failing(DecisionHookFailure.INVALID_DECISION)
                : answering(outcome, outcome == DecisionHookOutcome.ESCALATE
                        || outcome == DecisionHookOutcome.REQUIRE_APPROVALS ? 1 : null);
        var decision = evaluate(consultation);

        assertThat(decision.nextStatus()).isNotEqualTo(QueryStatus.APPROVED);
    }

    @Test
    void aMatchedPolicyShortCircuitsTheHook() {
        givenPlan(false, true, 1);
        when(routingPolicyEngine.evaluate(eq(organizationId), eq(datasourceId), any()))
                .thenReturn(Optional.of(new RoutingMatch(UUID.randomUUID(), "P",
                        RoutingAction.AUTO_APPROVE, null, "matched")));
        var calls = new AtomicInteger();
        DecisionHookInvoker counting = (q, c, a) -> {
            calls.incrementAndGet();
            return Optional.of(DecisionHookConsultation.simulated(hookId, "OPA"));
        };

        var decision = evaluate(counting);

        assertThat(calls).hasValue(0);
        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.ROUTING_AUTO_APPROVE);
        assertThat(decision.hook()).isNull();
        assertThat(step(decision.trace(), QueryDecisionStepKind.DECISION_HOOK).outcome())
                .isEqualTo(StepOutcome.SKIP);
    }

    @Test
    void aFailedAiAnalysisNeverConsultsTheHook() {
        var calls = new AtomicInteger();
        DecisionHookInvoker counting = (q, c, a) -> {
            calls.incrementAndGet();
            return Optional.empty();
        };

        var decision = evaluator.evaluate(query(), AiOutcome.FAILED, null, -1, List.of(), null,
                null, counting, clock);

        assertThat(calls).hasValue(0);
        assertThat(decision.kind()).isEqualTo(QueryDecisionKind.AI_FAILED_PENDING_REVIEW);
    }

    @Test
    void noApplicableHookIsReportedAsNoMatch() {
        givenPlan(false, true, 1);
        givenNoPolicyMatch();
        givenNoGrants();

        var decision = evaluate(DecisionHookInvoker.NONE);

        assertThat(decision.hook()).isNull();
        var hook = step(decision.trace(), QueryDecisionStepKind.DECISION_HOOK);
        assertThat(hook.outcome()).isEqualTo(StepOutcome.NO_MATCH);
        assertThat(hook.reasonKey()).isEqualTo("workflow.decision.hook.none");
    }

    @Test
    void aSimulatedHookIsReportedButNeverChangesTheOutcome() {
        givenPlan(true, false, 1);
        givenNoPolicyMatch();
        givenNoGrants();

        var decision = evaluate((q, c, a) ->
                Optional.of(DecisionHookConsultation.simulated(hookId, "OPA")));

        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.APPROVED);
        var hook = step(decision.trace(), QueryDecisionStepKind.DECISION_HOOK);
        assertThat(hook.outcome()).isEqualTo(StepOutcome.SKIP);
        assertThat(hook.reasonKey()).isEqualTo("workflow.decision.hook.simulation");
    }

    @Test
    void theTraceCarriesTheHookStepBetweenRoutingAndTheGrant() {
        givenPlan(false, true, 1);
        givenNoPolicyMatch();
        givenNoGrants();

        var trace = evaluate(answering(DecisionHookOutcome.ALLOW, null)).trace();

        assertThat(trace.steps()).extracting("step").containsExactly(
                QueryDecisionStepKind.SQL_REVIEW, QueryDecisionStepKind.BYTES_SCANNED_CAP,
                QueryDecisionStepKind.DATA_BUDGET, QueryDecisionStepKind.ROUTING_POLICIES,
                QueryDecisionStepKind.DECISION_HOOK, QueryDecisionStepKind.GRANT_FAST_PATH,
                QueryDecisionStepKind.REVIEW_PLAN);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private QueryDecision evaluate(DecisionHookConsultation consultation) {
        return evaluate((q, c, a) -> Optional.of(consultation));
    }

    private QueryDecision evaluate(DecisionHookInvoker invoker) {
        return evaluator.evaluate(query(), AiOutcome.COMPLETED, RiskLevel.LOW, 5, List.of(), null,
                null, invoker, clock);
    }

    private DecisionHookConsultation answering(DecisionHookOutcome outcome, Integer approvals) {
        return new DecisionHookConsultation(hookId, "OPA", outcome, null, approvals, "because",
                200, 12L);
    }

    private DecisionHookConsultation failing(DecisionHookFailure failure) {
        return new DecisionHookConsultation(hookId, "OPA", DecisionHookOutcome.FAILED, failure, null,
                null, null, 2000L);
    }

    private static DecisionTraceStep step(DecisionTrace trace, QueryDecisionStepKind kind) {
        return trace.steps().stream().filter(s -> s.step() == kind).findFirst().orElseThrow();
    }

    private QueryRequestSnapshot query() {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null, "203.0.113.7",
                "curl/8.4.0", false);
    }

    private void givenPlan(boolean autoApprove, boolean requiresHumanApproval, int minApprovals) {
        when(reviewPlanLookupService.findForDatasource(eq(datasourceId)))
                .thenReturn(Optional.of(new ReviewPlanSnapshot(UUID.randomUUID(), organizationId,
                        true, requiresHumanApproval && !autoApprove, minApprovals, false, 1,
                        List.of(new ApproverRule(null, "REVIEWER", 1)), List.of())));
    }

    private void givenNoPolicyMatch() {
        when(routingPolicyEngine.evaluate(eq(organizationId), eq(datasourceId), any()))
                .thenReturn(Optional.empty());
    }

    private void givenNoGrants() {
        when(accessGrantLookupService.findActivePreApprovedGrants(organizationId, submitterId,
                datasourceId)).thenReturn(List.of());
    }

    private void givenActiveGrant() {
        when(accessGrantLookupService.findActivePreApprovedGrants(organizationId, submitterId,
                datasourceId)).thenReturn(List.of(new AccessGrantView(UUID.randomUUID(),
                organizationId, submitterId, datasourceId, true, false, false, List.of(), List.of(),
                AccessGrantStatus.APPROVED, Instant.now().plusSeconds(3600), UUID.randomUUID(),
                "approver@x.io", Instant.now())));
    }
}
