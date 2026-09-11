package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DecisionTrace;
import com.bablsoft.accessflow.core.api.DecisionTraceStep;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.RolePermissionHolderLookupService;
import com.bablsoft.accessflow.core.api.StepOutcome;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.deploygov.api.DeploymentDecisionStepKind;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.deploygov.api.DeploymentRoutingAction;
import com.bablsoft.accessflow.deploygov.api.DeploymentSimulationInput;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultDeploymentSimulationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T18:30:00Z");

    @Mock private DeploymentPipelineLookupService pipelineLookupService;
    @Mock private EffectiveDeploymentPermissionResolver permissionResolver;
    @Mock private FreezeWindowEvaluator freezeWindowEvaluator;
    @Mock private DeploymentDecisionEvaluator decisionEvaluator;
    @Mock private DeploymentRoutingPolicyEngine routingEngine;
    @Mock private ReviewPlanLookupService reviewPlanLookupService;
    @Mock private RolePermissionHolderLookupService rolePermissionHolderLookupService;
    @Mock private UserQueryService userQueryService;

    private DefaultDeploymentSimulationService service;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID organizationId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultDeploymentSimulationService(pipelineLookupService, permissionResolver,
                freezeWindowEvaluator, decisionEvaluator, routingEngine, reviewPlanLookupService,
                rolePermissionHolderLookupService, userQueryService, clock);

        when(userQueryService.findById(userId)).thenReturn(Optional.of(user(userId, true)));
        when(userQueryService.findByIds(any())).thenReturn(List.of(user(reviewerId, true)));
        when(pipelineLookupService.findPipeline(pipelineId, organizationId))
                .thenReturn(Optional.of(pipeline(true, null)));
        when(pipelineLookupService.findEnvironment(pipelineId, environmentId))
                .thenReturn(Optional.of(environment(true, null, null, false)));
        when(permissionResolver.resolve(pipelineId, userId))
                .thenReturn(Optional.of(new EffectiveDeploymentPermission(pipelineId, userId, true,
                        false, null)));
        when(freezeWindowEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(decisionEvaluator.evaluate(any())).thenReturn(decision(QueryStatus.PENDING_REVIEW));
        when(routingEngine.evaluateAll(any(), any(), any())).thenReturn(List.of());
        when(reviewPlanLookupService.findById(any())).thenReturn(Optional.empty());
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.DEPLOYMENT_REVIEW)).thenReturn(List.of(reviewerId));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private UserView user(UUID id, boolean active) {
        return user(id, active, organizationId);
    }

    private static UserView user(UUID id, boolean active, UUID organization) {
        return new UserView(id, id + "@example.com", "Dana", UserRoleType.ANALYST, null, "ANALYST",
                organization, active, AuthProviderType.LOCAL, null, null, "en", false, false, null,
                null, null);
    }

    private DeploymentPipelineView pipeline(boolean active, UUID planReference) {
        return new DeploymentPipelineView(pipelineId, organizationId, "checkout-service",
                PipelineProvider.GITHUB_ACTIONS, null, null, planReference, true, null, active,
                null, null);
    }

    private DeploymentEnvironmentView environment(boolean requireReview, Integer approvals,
                                                  UUID planReference, boolean allowBreakGlass) {
        return new DeploymentEnvironmentView(environmentId, pipelineId, "production", 1,
                requireReview, approvals, planReference, allowBreakGlass, null, List.of());
    }

    private static DeploymentDecision decision(QueryStatus status) {
        var steps = List.of(
                DecisionTraceStep.of(DeploymentDecisionStepKind.ROUTING_POLICIES,
                        StepOutcome.NO_MATCH, "deploygov.decision.routing.no_match"),
                DecisionTraceStep.of(DeploymentDecisionStepKind.ENVIRONMENT_POLICY,
                        status == QueryStatus.APPROVED ? StepOutcome.ALLOW : StepOutcome.DENY,
                        "deploygov.decision.environment.requires_review"));
        return new DeploymentDecision(DeploymentDecisionKind.ENVIRONMENT_PENDING_REVIEW, status,
                null, 1, new DecisionTrace(steps, status));
    }

    private DeploymentSimulationInput input(Instant scheduledFor, Instant at) {
        return new DeploymentSimulationInput(userId, pipelineId, environmentId, "2.6.0",
                AiOutcome.COMPLETED, RiskLevel.HIGH, scheduledFor, at);
    }

    private DeploymentSimulationInput input() {
        return input(null, null);
    }

    private static DecisionTraceStep step(List<DecisionTraceStep> steps,
                                          DeploymentDecisionStepKind kind) {
        return steps.stream().filter(s -> s.step() == kind).findFirst().orElseThrow();
    }

    private static FreezeWindowEvaluator.ActiveFreeze freeze(FreezeBehavior behavior,
                                                             int specificity) {
        return new FreezeWindowEvaluator.ActiveFreeze(UUID.randomUUID(), behavior, "Q3 change freeze",
                specificity, NOW);
    }

    // ── The non-negotiable guarantee ──────────────────────────────────────────

    @Test
    void theSimulatorHasNoWayToCauseASideEffect() {
        // Structural, not behavioural: if a future change wires a repository, a state service, an
        // event publisher, an analyzer or an audit writer into this class, the guarantee in its
        // javadoc quietly stops being true. This is what makes it stay true.
        var forbidden = List.of("Persistence", "Repository", "StateService",
                "ApplicationEventPublisher", "EventPublisher", "Analyzer", "Notification",
                "Dispatcher", "ExecutionService", "Executor", "RestClient", "HttpClient",
                "Inserter", "AuditWriter", "Tracker", "DataSource", "JdbcTemplate", "Connection");
        var declared = Arrays.stream(DefaultDeploymentSimulationService.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(f -> f.getType().getSimpleName())
                .toList();

        assertThat(declared).isNotEmpty();
        assertThat(declared).allSatisfy(type ->
                assertThat(forbidden).noneSatisfy(bad -> assertThat(type).contains(bad)));
    }

    @Test
    void aSimulationOnlyEverReadsTheGateFunctionNeverTheGateService() {
        var result = service.simulate(organizationId, input());

        verify(decisionEvaluator).evaluate(any());
        verify(routingEngine, never()).evaluate(any(), any(), any());
        assertThat(result.releasable()).isFalse();
    }

    // ── Scoping ───────────────────────────────────────────────────────────────

    @Test
    void aUserInAnotherOrganizationIsNotFound() {
        when(userQueryService.findById(userId))
                .thenReturn(Optional.of(user(userId, true, UUID.randomUUID())));

        assertThatThrownBy(() -> service.simulate(organizationId, input()))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void aPipelineInAnotherOrganizationIsNotFound() {
        when(pipelineLookupService.findPipeline(pipelineId, organizationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.simulate(organizationId, input()))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void anEnvironmentOnAnotherPipelineIsNotFound() {
        when(pipelineLookupService.findEnvironment(pipelineId, environmentId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.simulate(organizationId, input()))
                .isInstanceOf(DeploymentEnvironmentNotFoundException.class);
    }

    // ── Shape ─────────────────────────────────────────────────────────────────

    @Test
    void everyStageIsReportedInOrderOnTheHappyPath() {
        var result = service.simulate(organizationId, input());

        assertThat(result.steps()).extracting("step")
                .containsExactly((Object[]) DeploymentDecisionStepKind.values());
        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(result.evaluatedAt()).isEqualTo(NOW);
        assertThat(result.caveats()).isEmpty();
    }

    @Test
    void everyStageIsStillReportedWhenTheTriggerIsRefused() {
        when(permissionResolver.resolve(pipelineId, userId)).thenReturn(Optional.empty());

        var result = service.simulate(organizationId, input());

        assertThat(result.steps()).extracting("step")
                .containsExactly((Object[]) DeploymentDecisionStepKind.values());
        assertThat(result.resultingStatus()).isNull();
        assertThat(result.releasable()).isFalse();
        assertThat(step(result.steps(), DeploymentDecisionStepKind.TRIGGER_PERMISSION))
                .satisfies(s -> {
                    assertThat(s.outcome()).isEqualTo(StepOutcome.DENY);
                    assertThat(s.reasonKey()).isEqualTo("deploygov.simulation.trigger.none");
                });
        assertThat(step(result.steps(), DeploymentDecisionStepKind.GATE_RELEASABILITY))
                .satisfies(s -> {
                    assertThat(s.outcome()).isEqualTo(StepOutcome.SKIP);
                    assertThat(s.reasonKey()).isEqualTo("deploygov.simulation.not_reached");
                });
        verify(decisionEvaluator, never()).evaluate(any());
    }

    // ── 1-2. Pipeline gates and the trigger grant ─────────────────────────────

    @Test
    void anInactivePipelineBlocksTheTrigger() {
        when(pipelineLookupService.findPipeline(pipelineId, organizationId))
                .thenReturn(Optional.of(pipeline(false, null)));

        var result = service.simulate(organizationId, input());

        var gate = step(result.steps(), DeploymentDecisionStepKind.PIPELINE_GATES);
        assertThat(gate.outcome()).isEqualTo(StepOutcome.DENY);
        assertThat(gate.details()).containsEntry("pipeline_name", "checkout-service")
                .containsEntry("environment_name", "production")
                .containsEntry("provider", "GITHUB_ACTIONS");
        assertThat(result.resultingStatus()).isNull();
    }

    @Test
    void aPermissionWithoutTriggerIsDistinguishableFromNoPermissionAtAll() {
        when(permissionResolver.resolve(pipelineId, userId))
                .thenReturn(Optional.of(new EffectiveDeploymentPermission(pipelineId, userId, false,
                        true, null)));

        var result = service.simulate(organizationId, input());

        assertThat(step(result.steps(), DeploymentDecisionStepKind.TRIGGER_PERMISSION).reasonKey())
                .isEqualTo("deploygov.simulation.trigger.not_granted");
    }

    @Test
    void anExpiringTriggerGrantReportsItsExpiry() {
        var expiry = Instant.parse("2026-10-01T00:00:00Z");
        when(permissionResolver.resolve(pipelineId, userId))
                .thenReturn(Optional.of(new EffectiveDeploymentPermission(pipelineId, userId, true,
                        false, expiry)));

        var result = service.simulate(organizationId, input());

        assertThat(step(result.steps(), DeploymentDecisionStepKind.TRIGGER_PERMISSION).details())
                .containsEntry("expires_at", expiry)
                .containsEntry("admin_bypass", false);
    }

    // ── 3. Freeze windows ─────────────────────────────────────────────────────

    @Test
    void aRejectWindowRefusesTheTriggerOutright() {
        when(freezeWindowEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(Optional.of(freeze(FreezeBehavior.REJECT, 1)));

        var result = service.simulate(organizationId, input());

        var step = step(result.steps(), DeploymentDecisionStepKind.FREEZE_WINDOW);
        assertThat(step.outcome()).isEqualTo(StepOutcome.DENY);
        assertThat(step.details()).containsEntry("behavior", "REJECT")
                .containsEntry("scope", "PIPELINE")
                .containsEntry("reason_text", "Q3 change freeze");
        assertThat(result.resultingStatus()).isNull();
        verify(decisionEvaluator, never()).evaluate(any());
    }

    @Test
    void aHoldWindowLetsTheTriggerThroughAndWithholdsTheRelease() {
        when(freezeWindowEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(Optional.of(freeze(FreezeBehavior.HOLD, 2)));
        when(decisionEvaluator.evaluate(any())).thenReturn(new DeploymentDecision(
                DeploymentDecisionKind.ENVIRONMENT_APPROVED, QueryStatus.APPROVED, null, null,
                decision(QueryStatus.APPROVED).trace()));

        var result = service.simulate(organizationId, input());

        var step = step(result.steps(), DeploymentDecisionStepKind.FREEZE_WINDOW);
        assertThat(step.outcome()).isEqualTo(StepOutcome.MATCH);
        assertThat(step.details()).containsEntry("scope", "ENVIRONMENT");
        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.APPROVED);
        // The decision approved it; the gate still says no. That gap is the whole point.
        assertThat(result.releasable()).isFalse();
        assertThat(step(result.steps(), DeploymentDecisionStepKind.GATE_RELEASABILITY).details())
                .containsEntry("frozen", true);
    }

    @Test
    void anOrgWideWindowIsReportedAsSuch() {
        when(freezeWindowEvaluator.evaluate(any(), any(), any(), any()))
                .thenReturn(Optional.of(freeze(FreezeBehavior.HOLD, 0)));

        var result = service.simulate(organizationId, input());

        assertThat(step(result.steps(), DeploymentDecisionStepKind.FREEZE_WINDOW).details())
                .containsEntry("scope", "ORGANIZATION");
    }

    @Test
    void noWindowIsANoMatchRatherThanAnAllow() {
        var result = service.simulate(organizationId, input());

        var step = step(result.steps(), DeploymentDecisionStepKind.FREEZE_WINDOW);
        assertThat(step.outcome()).isEqualTo(StepOutcome.NO_MATCH);
        assertThat(step.details()).isEmpty();
    }

    // ── The evaluation instant ────────────────────────────────────────────────

    @Test
    void aSuppliedInstantReachesTheFreezeEvaluatorAndTheEvaluator() {
        var at = Instant.parse("2026-09-12T22:00:00Z");

        var result = service.simulate(organizationId, input(null, at));

        verify(freezeWindowEvaluator).evaluate(eq(organizationId), eq(pipelineId), eq(environmentId),
                eq(at));
        var captor = ArgumentCaptor.forClass(DeploymentDecisionInput.class);
        verify(decisionEvaluator).evaluate(captor.capture());
        assertThat(captor.getValue().at()).isEqualTo(at);
        assertThat(result.evaluatedAt()).isEqualTo(at);
    }

    @Test
    void anAbsentInstantDefaultsToNow() {
        service.simulate(organizationId, input());

        verify(freezeWindowEvaluator).evaluate(eq(organizationId), eq(pipelineId), eq(environmentId),
                eq(NOW));
    }

    @Test
    void theEnvironmentPolicyReachesTheEvaluatorWithThePlanOverrideResolved() {
        var pipelinePlan = UUID.randomUUID();
        when(pipelineLookupService.findPipeline(pipelineId, organizationId))
                .thenReturn(Optional.of(pipeline(true, pipelinePlan)));
        when(pipelineLookupService.findEnvironment(pipelineId, environmentId))
                .thenReturn(Optional.of(environment(true, 3, planId, false)));

        service.simulate(organizationId, input());

        var captor = ArgumentCaptor.forClass(DeploymentDecisionInput.class);
        verify(decisionEvaluator).evaluate(captor.capture());
        assertThat(captor.getValue().reviewPlanId()).isEqualTo(planId);
        assertThat(captor.getValue().environmentRequiredApprovals()).isEqualTo(3);
        assertThat(captor.getValue().environmentName()).isEqualTo("production");
        assertThat(captor.getValue().version()).isEqualTo("2.6.0");
    }

    // ── 4. The full policy list ───────────────────────────────────────────────

    @Test
    void theRoutingStepCarriesEveryPolicyWithTheDecisiveOneMarked() {
        var winner = UUID.randomUUID();
        var loser = UUID.randomUUID();
        when(decisionEvaluator.evaluate(any())).thenReturn(new DeploymentDecision(
                DeploymentDecisionKind.ROUTING_ESCALATE, QueryStatus.PENDING_REVIEW,
                new DeploymentRoutingPolicyEngine.RoutingMatch(winner, "winner",
                        DeploymentRoutingAction.ESCALATE, 1),
                2, decision(QueryStatus.PENDING_REVIEW).trace()));
        when(routingEngine.evaluateAll(any(), any(), any())).thenReturn(List.of(
                new DeploymentRoutingPolicyEngine.PolicyEvaluation(loser, "loser", 5,
                        DeploymentRoutingAction.AUTO_APPROVE, null, false, false),
                new DeploymentRoutingPolicyEngine.PolicyEvaluation(winner, "winner", 10,
                        DeploymentRoutingAction.ESCALATE, 1, true, true)));

        var result = service.simulate(organizationId, input());

        var policies = step(result.steps(), DeploymentDecisionStepKind.ROUTING_POLICIES).details()
                .get("policies");
        assertThat(policies.toString()).contains("decisive=true").contains("loser");
    }

    @Test
    void aFailedAnalysisReportsNoPolicyListBecauseProductionNeverRoutesOne() {
        when(decisionEvaluator.evaluate(any())).thenReturn(new DeploymentDecision(
                DeploymentDecisionKind.AI_FAILED_PENDING_REVIEW, QueryStatus.PENDING_REVIEW, null, 1,
                decision(QueryStatus.PENDING_REVIEW).trace()));

        var result = service.simulate(organizationId, input());

        assertThat(step(result.steps(), DeploymentDecisionStepKind.ROUTING_POLICIES).details())
                .doesNotContainKey("policies");
        verify(routingEngine, never()).evaluateAll(any(), any(), any());
    }

    // ── 6. Reviewers ──────────────────────────────────────────────────────────

    @Test
    void reviewersArePermissionHoldersMinusTheSubmitter() {
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.DEPLOYMENT_REVIEW)).thenReturn(List.of(reviewerId, userId));

        var result = service.simulate(organizationId, input());

        assertThat(step(result.steps(), DeploymentDecisionStepKind.ELIGIBLE_REVIEWERS).outcome())
                .isEqualTo(StepOutcome.ALLOW);
        verify(userQueryService).findByIds(List.of(reviewerId));
    }

    @Test
    void aPlanWithApproverRulesNarrowsEligibilityAndDropsTheSubmitter() {
        when(pipelineLookupService.findEnvironment(pipelineId, environmentId))
                .thenReturn(Optional.of(environment(true, null, planId, false)));
        when(reviewPlanLookupService.findById(planId)).thenReturn(Optional.of(
                new ReviewPlanSnapshot(planId, organizationId, true, true, 1, false, 1,
                        List.of(new ApproverRule(reviewerId, null, 1),
                                new ApproverRule(userId, null, 1)),
                        List.of())));

        var result = service.simulate(organizationId, input());

        var step = step(result.steps(), DeploymentDecisionStepKind.ELIGIBLE_REVIEWERS);
        assertThat(step.reasonKey()).isEqualTo("deploygov.simulation.reviewers.plan_approvers");
        assertThat(step.details().get("plan_approvers").toString())
                .contains(reviewerId.toString())
                .doesNotContain(userId.toString());
    }

    @Test
    void noReviewerOtherThanTheSubmitterIsADenial() {
        when(rolePermissionHolderLookupService.findUserIdsWithPermission(organizationId,
                Permission.DEPLOYMENT_REVIEW)).thenReturn(List.of(userId));

        var result = service.simulate(organizationId, input());

        assertThat(step(result.steps(), DeploymentDecisionStepKind.ELIGIBLE_REVIEWERS).reasonKey())
                .isEqualTo("deploygov.simulation.reviewers.none");
    }

    // ── 7-8. Schedule and the gate ────────────────────────────────────────────

    @Test
    void anImmediateDeploymentPassesTheScheduleStage() {
        var result = service.simulate(organizationId, input());

        var step = step(result.steps(), DeploymentDecisionStepKind.SCHEDULED_RELEASE);
        assertThat(step.outcome()).isEqualTo(StepOutcome.ALLOW);
        assertThat(step.reasonKey()).isEqualTo("deploygov.simulation.schedule.immediate");
        assertThat(step.details()).containsEntry("evaluated_at", NOW);
    }

    @Test
    void aFutureScheduleWithholdsTheRelease() {
        var scheduled = NOW.plusSeconds(3600);
        when(decisionEvaluator.evaluate(any())).thenReturn(new DeploymentDecision(
                DeploymentDecisionKind.ENVIRONMENT_APPROVED, QueryStatus.APPROVED, null, null,
                decision(QueryStatus.APPROVED).trace()));

        var result = service.simulate(organizationId, input(scheduled, null));

        assertThat(step(result.steps(), DeploymentDecisionStepKind.SCHEDULED_RELEASE))
                .satisfies(s -> {
                    assertThat(s.outcome()).isEqualTo(StepOutcome.DENY);
                    assertThat(s.reasonKey()).isEqualTo("deploygov.simulation.schedule.pending");
                });
        assertThat(result.releasable()).isFalse();
    }

    @Test
    void aPastScheduleOnAnApprovedUnfrozenDeploymentOpensTheGate() {
        when(decisionEvaluator.evaluate(any())).thenReturn(new DeploymentDecision(
                DeploymentDecisionKind.ENVIRONMENT_APPROVED, QueryStatus.APPROVED, null, null,
                decision(QueryStatus.APPROVED).trace()));

        var result = service.simulate(organizationId, input(NOW.minusSeconds(60), null));

        assertThat(step(result.steps(), DeploymentDecisionStepKind.SCHEDULED_RELEASE).reasonKey())
                .isEqualTo("deploygov.simulation.schedule.due");
        assertThat(result.releasable()).isTrue();
        var gate = step(result.steps(), DeploymentDecisionStepKind.GATE_RELEASABILITY);
        assertThat(gate.outcome()).isEqualTo(StepOutcome.ALLOW);
        assertThat(gate.details()).containsEntry("releasable", true)
                .containsEntry("status", "APPROVED")
                .containsEntry("frozen", false);
    }

    @Test
    void aDeploymentStillInReviewIsNeverReleasable() {
        var result = service.simulate(organizationId, input());

        assertThat(result.releasable()).isFalse();
        assertThat(step(result.steps(), DeploymentDecisionStepKind.GATE_RELEASABILITY).details())
                .containsEntry("status", "PENDING_REVIEW");
    }

    // ── 9. Break-glass ────────────────────────────────────────────────────────

    @Test
    void breakGlassNeedsBothTheGrantAndTheEnvironmentOptIn() {
        when(permissionResolver.resolve(pipelineId, userId))
                .thenReturn(Optional.of(new EffectiveDeploymentPermission(pipelineId, userId, true,
                        true, null)));
        when(pipelineLookupService.findEnvironment(pipelineId, environmentId))
                .thenReturn(Optional.of(environment(true, null, null, true)));

        var result = service.simulate(organizationId, input());

        var step = step(result.steps(), DeploymentDecisionStepKind.BREAK_GLASS);
        assertThat(step.outcome()).isEqualTo(StepOutcome.ALLOW);
        assertThat(step.details()).containsEntry("can_break_glass", true)
                .containsEntry("environment_allows_break_glass", true);
    }

    @Test
    void anEnvironmentThatForbidsBreakGlassDeniesEvenAGrantedUser() {
        when(permissionResolver.resolve(pipelineId, userId))
                .thenReturn(Optional.of(new EffectiveDeploymentPermission(pipelineId, userId, true,
                        true, null)));

        var result = service.simulate(organizationId, input());

        var step = step(result.steps(), DeploymentDecisionStepKind.BREAK_GLASS);
        assertThat(step.outcome()).isEqualTo(StepOutcome.DENY);
        assertThat(step.reasonKey())
                .isEqualTo("deploygov.simulation.break_glass.environment_forbids");
    }

    @Test
    void anUngrantedUserOnAnOptedInEnvironmentIsDeniedForTheOtherReason() {
        when(pipelineLookupService.findEnvironment(pipelineId, environmentId))
                .thenReturn(Optional.of(environment(true, null, null, true)));

        var result = service.simulate(organizationId, input());

        assertThat(step(result.steps(), DeploymentDecisionStepKind.BREAK_GLASS).reasonKey())
                .isEqualTo("deploygov.simulation.break_glass.not_granted");
    }

    @Test
    void anInactiveReviewerIsNotOffered() {
        when(userQueryService.findByIds(any())).thenReturn(List.of(user(reviewerId, false)));

        var result = service.simulate(organizationId, input());

        assertThat(step(result.steps(), DeploymentDecisionStepKind.ELIGIBLE_REVIEWERS).details()
                .get("reviewers")).isEqualTo(List.of());
    }
}
