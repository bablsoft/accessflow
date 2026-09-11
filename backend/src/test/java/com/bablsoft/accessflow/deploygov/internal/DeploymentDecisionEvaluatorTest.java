package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.DecisionTrace;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.deploygov.api.DeploymentDecisionStepKind;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.routing.DeploymentRoutingPolicyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
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
class DeploymentDecisionEvaluatorTest {

    private static final Instant AT = Instant.parse("2026-08-21T17:30:00Z");

    @Mock private DeploymentRoutingPolicyEngine routingEngine;
    @Mock private ReviewPlanLookupService reviewPlanLookupService;

    private DeploymentDecisionEvaluator evaluator;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        evaluator = new DeploymentDecisionEvaluator(routingEngine, reviewPlanLookupService);
        when(routingEngine.evaluate(any(), any(), any())).thenReturn(null);
        when(reviewPlanLookupService.findById(any())).thenReturn(Optional.empty());
    }

    private DeploymentDecisionInput input(boolean requireReview, Integer environmentApprovals,
                                          UUID reviewPlanId, AiOutcome aiOutcome, RiskLevel risk) {
        return new DeploymentDecisionInput(organizationId, pipelineId,
                PipelineProvider.GITHUB_ACTIONS, "production", requireReview, environmentApprovals,
                reviewPlanId, "2.6.0", aiOutcome, risk, AT);
    }

    private void stubMatch(DeploymentRoutingAction action, Integer requiredApprovals) {
        when(routingEngine.evaluate(any(), any(), any())).thenReturn(
                new DeploymentRoutingPolicyEngine.RoutingMatch(policyId, "the policy", action,
                        requiredApprovals));
    }

    private static ReviewPlanSnapshot plan(int minApprovals, boolean requiresHumanApproval) {
        return new ReviewPlanSnapshot(UUID.randomUUID(), UUID.randomUUID(), true,
                requiresHumanApproval, minApprovals, false, 1, List.of(), List.of());
    }

    private static DecisionTraceStep step(DecisionTrace trace, DeploymentDecisionStepKind kind) {
        return trace.steps().stream().filter(s -> s.step() == kind).findFirst().orElseThrow();
    }

    // ── Environment policy, no routing match ──────────────────────────────────

    @Test
    void anEnvironmentThatRequiresReviewGoesToReview() {
        var decision = evaluator.evaluate(input(true, null, null, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(DeploymentDecisionKind.ENVIRONMENT_PENDING_REVIEW);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(decision.effectiveApprovals()).isEqualTo(1);
        assertThat(step(decision.trace(), DeploymentDecisionStepKind.ROUTING_POLICIES).outcome())
                .isEqualTo(StepOutcome.NO_MATCH);
        assertThat(step(decision.trace(), DeploymentDecisionStepKind.ENVIRONMENT_POLICY))
                .satisfies(s -> {
                    assertThat(s.outcome()).isEqualTo(StepOutcome.DENY);
                    assertThat(s.reasonKey())
                            .isEqualTo("deploygov.decision.environment.requires_review");
                });
    }

    @Test
    void anEnvironmentThatDoesNotRequireReviewIsApproved() {
        var decision = evaluator.evaluate(input(false, null, null, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(DeploymentDecisionKind.ENVIRONMENT_APPROVED);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.APPROVED);
        assertThat(step(decision.trace(), DeploymentDecisionStepKind.ENVIRONMENT_POLICY).outcome())
                .isEqualTo(StepOutcome.ALLOW);
    }

    @Test
    void aPlanThatWaivesHumanApprovalRelaxesTheEnvironmentFlag() {
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(plan(3, false)));

        var decision = evaluator.evaluate(input(true, null, planId, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(DeploymentDecisionKind.ENVIRONMENT_APPROVED);
        assertThat(step(decision.trace(), DeploymentDecisionStepKind.ENVIRONMENT_POLICY).reasonKey())
                .isEqualTo("deploygov.decision.environment.plan_waives_approval");
    }

    @Test
    void theEnvironmentApprovalOverrideBeatsThePlanMinimum() {
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(plan(2, true)));

        var decision = evaluator.evaluate(input(true, 5, planId, AiOutcome.SKIPPED, null));

        assertThat(decision.effectiveApprovals()).isEqualTo(5);
        assertThat(step(decision.trace(), DeploymentDecisionStepKind.ENVIRONMENT_POLICY).details())
                .containsEntry("environment_required_approvals", 5)
                .containsEntry("min_approvals_required", 2)
                .containsEntry("effective_min_approvals", 5);
    }

    @Test
    void thePlanMinimumAppliesWhenTheEnvironmentHasNoOverride() {
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(plan(3, true)));

        var decision = evaluator.evaluate(input(true, null, planId, AiOutcome.SKIPPED, null));

        assertThat(decision.effectiveApprovals()).isEqualTo(3);
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    @Test
    void routingWinsOutrightOverTheEnvironmentPolicy() {
        stubMatch(DeploymentRoutingAction.AUTO_APPROVE, null);

        var decision = evaluator.evaluate(input(true, 4, null, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(DeploymentDecisionKind.ROUTING_AUTO_APPROVE);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.APPROVED);
        var routing = step(decision.trace(), DeploymentDecisionStepKind.ROUTING_POLICIES);
        assertThat(routing.outcome()).isEqualTo(StepOutcome.MATCH);
        assertThat(routing.reasonArgs()).containsExactly("the policy", "AUTO_APPROVE");
        assertThat(step(decision.trace(), DeploymentDecisionStepKind.ENVIRONMENT_POLICY).outcome())
                .isEqualTo(StepOutcome.SKIP);
    }

    @Test
    void routingAutoRejectRejects() {
        stubMatch(DeploymentRoutingAction.AUTO_REJECT, null);

        var decision = evaluator.evaluate(input(false, null, null, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(DeploymentDecisionKind.ROUTING_AUTO_REJECT);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.REJECTED);
    }

    @Test
    void requireApprovalsReplacesTheResolvedCount() {
        stubMatch(DeploymentRoutingAction.REQUIRE_APPROVALS, 2);

        var decision = evaluator.evaluate(input(true, 4, null, AiOutcome.SKIPPED, null));

        assertThat(decision.effectiveApprovals()).isEqualTo(2);
    }

    @Test
    void requireApprovalsFallsBackToOne() {
        stubMatch(DeploymentRoutingAction.REQUIRE_APPROVALS, null);

        var decision = evaluator.evaluate(input(true, 4, null, AiOutcome.SKIPPED, null));

        assertThat(decision.effectiveApprovals()).isEqualTo(1);
    }

    @Test
    void escalateAddsToTheResolvedCount() {
        var decision0 = evaluator.evaluate(input(true, 2, null, AiOutcome.SKIPPED, null));
        assertThat(decision0.effectiveApprovals()).isEqualTo(2);

        stubMatch(DeploymentRoutingAction.ESCALATE, 3);
        var decision = evaluator.evaluate(input(true, 2, null, AiOutcome.SKIPPED, null));

        assertThat(decision.kind()).isEqualTo(DeploymentDecisionKind.ROUTING_ESCALATE);
        assertThat(decision.effectiveApprovals()).isEqualTo(5);
    }

    @Test
    void escalateAddsOneByDefault() {
        stubMatch(DeploymentRoutingAction.ESCALATE, null);

        var decision = evaluator.evaluate(input(true, 2, null, AiOutcome.SKIPPED, null));

        assertThat(decision.effectiveApprovals()).isEqualTo(3);
    }

    // ── The AI outcome and the evaluation instant ─────────────────────────────

    @Test
    void theEvaluationInstantAndTheReleaseShapeReachTheEngine() {
        evaluator.evaluate(input(true, null, null, AiOutcome.COMPLETED, RiskLevel.CRITICAL));

        var captor = ArgumentCaptor.forClass(DeploymentRoutingPolicyEngine.RoutingContext.class);
        verify(routingEngine).evaluate(any(), any(), captor.capture());
        // `at` is what makes a hypothetical Friday-evening release answerable: the engine's
        // time-window leaf reads it directly.
        assertThat(captor.getValue().at()).isEqualTo(AT);
        assertThat(captor.getValue().riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(captor.getValue().environmentName()).isEqualTo("production");
        assertThat(captor.getValue().version()).isEqualTo("2.6.0");
        assertThat(captor.getValue().provider()).isEqualTo(PipelineProvider.GITHUB_ACTIONS);
    }

    @Test
    void onlyACompletedAnalysisCarriesARiskSignalIntoRouting() {
        evaluator.evaluate(input(true, null, null, AiOutcome.SKIPPED, RiskLevel.CRITICAL));

        var captor = ArgumentCaptor.forClass(DeploymentRoutingPolicyEngine.RoutingContext.class);
        verify(routingEngine).evaluate(any(), any(), captor.capture());
        assertThat(captor.getValue().riskLevel()).isNull();
    }

    @Test
    void aFailedAnalysisGoesStraightToReviewWithoutRouting() {
        var decision = evaluator.evaluate(input(false, null, null, AiOutcome.FAILED, null));

        assertThat(decision.kind()).isEqualTo(DeploymentDecisionKind.AI_FAILED_PENDING_REVIEW);
        assertThat(decision.nextStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        verify(routingEngine, never()).evaluate(any(), any(), any());
        assertThat(step(decision.trace(), DeploymentDecisionStepKind.ROUTING_POLICIES).reasonKey())
                .isEqualTo("deploygov.decision.routing.skipped_ai_failed");
    }

    @Test
    void aFailedAnalysisStillHonoursTheResolvedApprovalCount() {
        // A provider outage must not quietly reduce the number of humans a production release needs.
        var decision = evaluator.evaluate(input(true, 4, null, AiOutcome.FAILED, null));

        assertThat(decision.effectiveApprovals()).isEqualTo(4);
        assertThat(step(decision.trace(), DeploymentDecisionStepKind.ENVIRONMENT_POLICY).details())
                .containsEntry("effective_min_approvals", 4);
    }

    // ── The trace shape ───────────────────────────────────────────────────────

    @Test
    void everyBranchEmitsBothLiveStagesExactlyOnce() {
        for (var outcome : AiOutcome.values()) {
            var decision = evaluator.evaluate(input(true, null, null, outcome, null));

            assertThat(decision.trace().steps()).as("%s", outcome)
                    .extracting(DecisionTraceStep::step)
                    .containsExactly(DeploymentDecisionStepKind.ROUTING_POLICIES,
                            DeploymentDecisionStepKind.ENVIRONMENT_POLICY);
            assertThat(decision.trace().resultingStatus()).isEqualTo(decision.nextStatus());
        }
    }
}
