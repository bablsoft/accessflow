package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiDecisionStepKind;
import com.bablsoft.accessflow.apigov.api.ApiRoutingAction;
import com.bablsoft.accessflow.apigov.internal.routing.ApiRoutingPolicyEngine;
import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.DecisionTrace;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.StepOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiDecisionEvaluatorTest {

    @Mock private ApiRoutingPolicyEngine routingEngine;
    @Mock private ReviewPlanLookupService reviewPlanLookupService;

    private ApiDecisionEvaluator evaluator;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        evaluator = new ApiDecisionEvaluator(routingEngine, reviewPlanLookupService);
        when(routingEngine.evaluate(any(), any(), any())).thenReturn(null);
        when(reviewPlanLookupService.findById(any())).thenReturn(Optional.empty());
    }

    private ApiDecisionInput input(boolean write, boolean requireReads, boolean requireWrites,
                                   UUID reviewPlanId, AiOutcome aiOutcome, RiskLevel risk) {
        return new ApiDecisionInput(organizationId, connectorId, reviewPlanId, requireReads,
                requireWrites, write ? "POST" : "GET", write, "op-1", aiOutcome, risk);
    }

    private void stubMatch(ApiRoutingAction action, Integer requiredApprovals) {
        when(routingEngine.evaluate(any(), any(), any())).thenReturn(
                new ApiRoutingPolicyEngine.RoutingMatch(policyId, "the policy", action,
                        requiredApprovals));
    }

    private static ReviewPlanSnapshot plan(int minApprovals, boolean requiresHumanApproval) {
        return new ReviewPlanSnapshot(UUID.randomUUID(), UUID.randomUUID(), true,
                requiresHumanApproval, minApprovals, false, 1, List.of(), List.of());
    }

    private static DecisionTraceStep step(DecisionTrace trace, ApiDecisionStepKind kind) {
        return trace.steps().stream().filter(s -> s.step() == kind).findFirst().orElseThrow();
    }

    // ── Connector policy, no routing match ────────────────────────────────────

    @Test
    void aReadOnAConnectorThatDoesNotReviewReadsIsApproved() {
        var decision = evaluator.evaluate(
                input(false, false, true, null, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(ApiDecisionKind.CONNECTOR_APPROVED);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(decision.effectiveApprovals()).isNull();
        assertThat(step(decision.trace(), ApiDecisionStepKind.ROUTING_POLICIES).outcome())
                .isEqualTo(StepOutcome.NO_MATCH);
        assertThat(step(decision.trace(), ApiDecisionStepKind.REVIEW_REQUIREMENT))
                .satisfies(s -> {
                    assertThat(s.outcome()).isEqualTo(StepOutcome.ALLOW);
                    assertThat(s.reasonKey()).isEqualTo("apigov.decision.review.not_required");
                });
    }

    @Test
    void aWriteOnAConnectorThatReviewsWritesGoesToReview() {
        var decision = evaluator.evaluate(input(true, false, true, null, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(ApiDecisionKind.CONNECTOR_PENDING_REVIEW);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(decision.effectiveApprovals()).isEqualTo(1);
        assertThat(step(decision.trace(), ApiDecisionStepKind.REVIEW_REQUIREMENT).reasonKey())
                .isEqualTo("apigov.decision.review.required_write");
    }

    @Test
    void aReadOnAConnectorThatReviewsReadsGoesToReviewWithItsOwnReasonKey() {
        var decision = evaluator.evaluate(input(false, true, false, null, AiOutcome.SKIPPED, null));

        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(step(decision.trace(), ApiDecisionStepKind.REVIEW_REQUIREMENT).reasonKey())
                .isEqualTo("apigov.decision.review.required_read");
    }

    @Test
    void aPlanThatWaivesHumanApprovalOverridesTheConnectorFlag() {
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(plan(3, false)));

        var decision = evaluator.evaluate(input(true, true, true, planId, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(ApiDecisionKind.CONNECTOR_APPROVED);
        assertThat(step(decision.trace(), ApiDecisionStepKind.REVIEW_REQUIREMENT).reasonKey())
                .isEqualTo("apigov.decision.review.plan_waives_approval");
    }

    @Test
    void thePlansMinimumIsTheApprovalCountWhenReviewIsRequired() {
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(plan(4, true)));

        var decision = evaluator.evaluate(input(true, false, true, planId, AiOutcome.SKIPPED, null));

        assertThat(decision.effectiveApprovals()).isEqualTo(4);
        assertThat(step(decision.trace(), ApiDecisionStepKind.REVIEW_REQUIREMENT).details())
                .containsEntry("effective_min_approvals", 4)
                .containsEntry("require_review_writes", true);
    }

    @Test
    void aConnectorWithoutAPlanReportsNullPlanDetailsRatherThanOmittingThem() {
        var decision = evaluator.evaluate(input(true, false, true, null, AiOutcome.SKIPPED, null));

        var details = step(decision.trace(), ApiDecisionStepKind.REVIEW_REQUIREMENT).details();
        assertThat(details).containsKey("review_plan_id");
        assertThat(details.get("review_plan_id")).isNull();
        assertThat(details.get("requires_human_approval")).isNull();
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    @Test
    void routingAutoApproveWins() {
        stubMatch(ApiRoutingAction.AUTO_APPROVE, null);

        var decision = evaluator.evaluate(input(true, true, true, null, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(ApiDecisionKind.ROUTING_AUTO_APPROVE);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.APPROVED);
        var routing = step(decision.trace(), ApiDecisionStepKind.ROUTING_POLICIES);
        assertThat(routing.outcome()).isEqualTo(StepOutcome.MATCH);
        assertThat(routing.reasonArgs()).containsExactly("the policy", "AUTO_APPROVE");
        assertThat(routing.details())
                .containsEntry("matched_policy_id", policyId)
                .containsEntry("matched_policy_name", "the policy");
        assertThat(step(decision.trace(), ApiDecisionStepKind.REVIEW_REQUIREMENT).outcome())
                .isEqualTo(StepOutcome.SKIP);
    }

    @Test
    void routingAutoRejectWins() {
        stubMatch(ApiRoutingAction.AUTO_REJECT, null);

        var decision = evaluator.evaluate(input(false, false, false, null, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(ApiDecisionKind.ROUTING_AUTO_REJECT);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.REJECTED);
    }

    @Test
    void requireApprovalsReplacesTheResolvedCount() {
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(plan(5, true)));
        stubMatch(ApiRoutingAction.REQUIRE_APPROVALS, 2);

        var decision = evaluator.evaluate(input(true, false, true, planId, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(ApiDecisionKind.ROUTING_REQUIRE_APPROVALS);
        assertThat(decision.effectiveApprovals()).isEqualTo(2);
    }

    @Test
    void requireApprovalsFallsBackToOne() {
        stubMatch(ApiRoutingAction.REQUIRE_APPROVALS, null);

        var decision = evaluator.evaluate(input(true, false, true, null, AiOutcome.SKIPPED, null));

        assertThat(decision.effectiveApprovals()).isEqualTo(1);
    }

    @Test
    void escalateAddsToTheResolvedCount() {
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(plan(2, true)));
        stubMatch(ApiRoutingAction.ESCALATE, 3);

        var decision = evaluator.evaluate(input(true, false, true, planId, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(ApiDecisionKind.ROUTING_ESCALATE);
        assertThat(decision.effectiveApprovals()).isEqualTo(5);
    }

    @Test
    void escalateAddsOneOverTheDefaultOfOneWhenThereIsNoPlan() {
        stubMatch(ApiRoutingAction.ESCALATE, null);

        var decision = evaluator.evaluate(input(true, false, true, null, AiOutcome.SKIPPED, null));

        assertThat(decision.effectiveApprovals()).isEqualTo(2);
    }

    // ── The AI outcome ────────────────────────────────────────────────────────

    @Test
    void onlyACompletedAnalysisCarriesARiskSignalIntoRouting() {
        evaluator.evaluate(input(true, false, true, null, AiOutcome.SKIPPED, RiskLevel.CRITICAL));

        var captor = ArgumentCaptor.forClass(ApiRoutingPolicyEngine.RoutingContext.class);
        verify(routingEngine).evaluate(any(), any(), captor.capture());
        // A caller that supplies a verdict on the SKIPPED branch must not have it leak into a
        // branch production never sees one on.
        assertThat(captor.getValue().riskLevel()).isNull();
    }

    @Test
    void aCompletedAnalysisPassesTheVerdictAndTheCallShape() {
        evaluator.evaluate(input(true, false, true, null, AiOutcome.COMPLETED, RiskLevel.HIGH));

        var captor = ArgumentCaptor.forClass(ApiRoutingPolicyEngine.RoutingContext.class);
        verify(routingEngine).evaluate(any(), any(), captor.capture());
        assertThat(captor.getValue().riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(captor.getValue().write()).isTrue();
        assertThat(captor.getValue().verb()).isEqualTo("POST");
        assertThat(captor.getValue().operationId()).isEqualTo("op-1");
    }

    @Test
    void aFailedAnalysisGoesStraightToReviewWithoutRouting() {
        var decision = evaluator.evaluate(input(false, false, false, null, AiOutcome.FAILED, null));

        assertThat(decision.kind()).isEqualTo(ApiDecisionKind.AI_FAILED_PENDING_REVIEW);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        verify(routingEngine, never()).evaluate(any(), any(), any());
        assertThat(step(decision.trace(), ApiDecisionStepKind.ROUTING_POLICIES))
                .satisfies(s -> {
                    assertThat(s.outcome()).isEqualTo(StepOutcome.SKIP);
                    assertThat(s.reasonKey()).isEqualTo("apigov.decision.routing.skipped_ai_failed");
                });
        assertThat(step(decision.trace(), ApiDecisionStepKind.REVIEW_REQUIREMENT).outcome())
                .isEqualTo(StepOutcome.SKIP);
    }

    @Test
    void aFailedAnalysisStillHonoursThePlansApprovalCount() {
        // An analyzer outage must not quietly reduce the number of humans a call needs.
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(plan(3, true)));

        var decision = evaluator.evaluate(input(true, true, true, planId, AiOutcome.FAILED, null));

        assertThat(decision.effectiveApprovals()).isEqualTo(3);
        assertThat(step(decision.trace(), ApiDecisionStepKind.REVIEW_REQUIREMENT).details())
                .containsEntry("effective_min_approvals", 3);
    }

    @Test
    void aFailedAnalysisWithNoPlanNeedsOneApproval() {
        var decision = evaluator.evaluate(input(true, true, true, null, AiOutcome.FAILED, null));

        assertThat(decision.effectiveApprovals()).isEqualTo(1);
    }

    // ── The trace shape ───────────────────────────────────────────────────────

    @Test
    void everyBranchEmitsBothLiveStagesExactlyOnce() {
        for (var outcome : AiOutcome.values()) {
            var decision = evaluator.evaluate(input(true, false, true, null, outcome, null));

            assertThat(decision.trace().steps()).as("%s", outcome)
                    .extracting(DecisionTraceStep::step)
                    .containsExactly(ApiDecisionStepKind.ROUTING_POLICIES,
                            ApiDecisionStepKind.REVIEW_REQUIREMENT);
            assertThat(decision.trace().resultingStatus()).isEqualTo(decision.nextStatus());
        }
    }
}
