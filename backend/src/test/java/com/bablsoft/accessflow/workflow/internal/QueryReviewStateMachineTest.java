package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.access.api.AccessGrantLookupService;
import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessGrantView;
import com.bablsoft.accessflow.core.api.ApproverRule;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ReviewPlanLookupService;
import com.bablsoft.accessflow.core.api.ReviewPlanSnapshot;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisFailedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisSkippedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoApprovedEvent;
import com.bablsoft.accessflow.core.events.QueryAutoRejectedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingDecisionService;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingMatch;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingPolicyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;

import java.time.Instant;
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

@ExtendWith(MockitoExtension.class)
class QueryReviewStateMachineTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock ReviewPlanLookupService reviewPlanLookupService;
    @Mock QueryRequestStateService queryRequestStateService;
    @Mock SqlParserService sqlParserService;
    @Mock UserQueryService userQueryService;
    @Mock UserGroupService userGroupService;
    @Mock RoutingPolicyEngine routingPolicyEngine;
    @Mock RoutingDecisionService routingDecisionService;
    @Mock com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService behaviorAnomalyLookupService;
    @Mock AccessGrantLookupService accessGrantLookupService;
    @Mock MessageSource messageSource;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks QueryReviewStateMachine stateMachine;

    private final UUID queryId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID aiAnalysisId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final UUID grantId = UUID.randomUUID();

    @BeforeEach
    void stubSignals() {
        // Routing context is built before evaluation; keep the parser harmless for the
        // plan-fallthrough tests. evaluate() defaults to Optional.empty() unless a test overrides it.
        lenient().when(sqlParserService.parse(any()))
                .thenReturn(new SqlParseResult(QueryType.SELECT, "SELECT 1"));
    }

    @Test
    void aiCompletedTransitionsToApprovedWhenHumanApprovalNotRequired() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, false, RiskLevel.HIGH);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.HIGH));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.APPROVED);
        verify(eventPublisher).publishEvent(any(QueryAutoApprovedEvent.class));
    }

    @Test
    void aiCompletedAutoApprovesSelectWithLowRisk() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(true, true, RiskLevel.LOW);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.APPROVED);
        verify(eventPublisher).publishEvent(any(QueryAutoApprovedEvent.class));
    }

    @Test
    void aiCompletedAutoApprovesSelectWithMediumRisk() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(true, true, RiskLevel.MEDIUM);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.MEDIUM));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.APPROVED);
    }

    @Test
    void aiCompletedDoesNotAutoApproveSelectWithHighRisk() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(true, true, RiskLevel.HIGH);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.HIGH));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
        verify(eventPublisher).publishEvent(any(QueryReadyForReviewEvent.class));
    }

    @Test
    void aiCompletedDoesNotAutoApproveNonSelectQueryEvenWithLowRisk() {
        givenPendingAiQuery(QueryType.UPDATE);
        givenPlan(true, true, RiskLevel.LOW);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
    }

    @Test
    void aiCompletedDefaultsToPendingReviewWhenAutoApproveDisabled() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.LOW);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
        verify(eventPublisher).publishEvent(any(QueryReadyForReviewEvent.class));
    }

    @Test
    void aiCompletedRoutesToPendingReviewWhenNoReviewPlan() {
        givenPendingAiQuery(QueryType.SELECT);
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.empty());

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
    }

    @Test
    void aiCompletedIgnoresQueryNotInPendingAi() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW)));

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void aiCompletedIgnoresUnknownQuery() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
    }

    // ── Routing-policy decisions ──────────────────────────────────────────────

    @Test
    void aiCompletedAutoApprovesWhenPolicyMatches() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.HIGH);
        givenPolicyMatch(RoutingAction.AUTO_APPROVE, null);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.HIGH, 90));

        verify(routingDecisionService).applyDecision(eq(queryId), eq(QueryStatus.APPROVED),
                any(RoutingMatch.class), eq(null));
        verify(eventPublisher).publishEvent(any(QueryAutoApprovedEvent.class));
        // Plan fall-through must not run when a policy matched.
        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void aiCompletedAutoRejectsWhenPolicyMatches() {
        givenPendingAiQuery(QueryType.DELETE);
        givenPlan(false, true, RiskLevel.HIGH);
        givenPolicyMatch(RoutingAction.AUTO_REJECT, null);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.HIGH, 95));

        verify(routingDecisionService).applyDecision(eq(queryId), eq(QueryStatus.REJECTED),
                any(RoutingMatch.class), eq(null));
        verify(eventPublisher).publishEvent(any(QueryAutoRejectedEvent.class));
    }

    @Test
    void aiCompletedRequireApprovalsUsesAbsoluteCount() {
        givenPendingAiQuery(QueryType.UPDATE);
        givenPlan(false, true, RiskLevel.MEDIUM);
        givenPolicyMatch(RoutingAction.REQUIRE_APPROVALS, 3);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.MEDIUM, 50));

        verify(routingDecisionService).applyDecision(eq(queryId), eq(QueryStatus.PENDING_REVIEW),
                any(RoutingMatch.class), eq(3));
        verify(eventPublisher).publishEvent(any(QueryReadyForReviewEvent.class));
    }

    @Test
    void aiCompletedEscalateAddsDeltaToPlanMinimum() {
        givenPendingAiQuery(QueryType.UPDATE);
        // plan min approvals = 1 (see givenPlan)
        givenPlan(false, true, RiskLevel.HIGH);
        givenPolicyMatch(RoutingAction.ESCALATE, 2);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.HIGH, 80));

        // 1 (plan min) + 2 (escalate delta) = 3
        verify(routingDecisionService).applyDecision(eq(queryId), eq(QueryStatus.PENDING_REVIEW),
                any(RoutingMatch.class), eq(3));
        var event = ArgumentCaptor.forClass(QueryReadyForReviewEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        // The matched policy is carried on the event so the audit log records the escalation.
        assertThat(event.getValue().matchedPolicyId()).isEqualTo(policyId);
        assertThat(event.getValue().effectiveMinApprovals()).isEqualTo(3);
        assertThat(event.getValue().routingReason()).isEqualTo("matched");
    }

    @Test
    void aiSkippedAppliesPolicyAutoReject() {
        givenPendingAiQuery(QueryType.DELETE);
        givenPlan(false, true, RiskLevel.LOW);
        givenPolicyMatch(RoutingAction.AUTO_REJECT, null);

        stateMachine.onAiSkipped(new AiAnalysisSkippedEvent(queryId, "ai_analysis_enabled=false"));

        verify(routingDecisionService).applyDecision(eq(queryId), eq(QueryStatus.REJECTED),
                any(RoutingMatch.class), eq(null));
        verify(eventPublisher).publishEvent(any(QueryAutoRejectedEvent.class));
    }

    @Test
    void aiFailedAlwaysTransitionsToPendingReview() {
        givenPendingAiQuery(QueryType.SELECT);

        stateMachine.onAiFailed(new AiAnalysisFailedEvent(queryId, "provider error"));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
        verify(eventPublisher).publishEvent(any(QueryReadyForReviewEvent.class));
        // Plan and routing are never consulted on failure path
        verify(reviewPlanLookupService, never()).findForDatasource(any());
        verify(routingPolicyEngine, never()).evaluate(any(), any(), any());
    }

    @Test
    void aiFailedIgnoresQueryNotInPendingAi() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED)));

        stateMachine.onAiFailed(new AiAnalysisFailedEvent(queryId, "boom"));

        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void aiFailedIgnoresUnknownQuery() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        stateMachine.onAiFailed(new AiAnalysisFailedEvent(queryId, "boom"));

        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void aiSkippedAutoApprovesWhenHumanApprovalNotRequired() {
        givenPendingAiQuery(QueryType.UPDATE);
        givenPlan(false, false, RiskLevel.HIGH);

        stateMachine.onAiSkipped(new AiAnalysisSkippedEvent(queryId, "ai_analysis_enabled=false"));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.APPROVED);
        verify(eventPublisher).publishEvent(any(QueryAutoApprovedEvent.class));
    }

    @Test
    void aiSkippedRoutesToPendingReviewWhenHumanApprovalRequired() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.LOW);

        stateMachine.onAiSkipped(new AiAnalysisSkippedEvent(queryId, "ai_analysis_enabled=false"));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
        verify(eventPublisher).publishEvent(any(QueryReadyForReviewEvent.class));
    }

    @Test
    void aiSkippedRoutesToPendingReviewWhenNoReviewPlan() {
        givenPendingAiQuery(QueryType.SELECT);
        when(reviewPlanLookupService.findForDatasource(datasourceId)).thenReturn(Optional.empty());

        stateMachine.onAiSkipped(new AiAnalysisSkippedEvent(queryId, "ai_analysis_enabled=false"));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
        verify(eventPublisher).publishEvent(any(QueryReadyForReviewEvent.class));
    }

    @Test
    void aiSkippedDoesNotFastPathSelectsWithAutoApproveReads() {
        // Without an AI risk signal, the SELECT/low-risk fast-path cannot apply — the query
        // must still go through human review when the plan requires it.
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(true, true, RiskLevel.LOW);

        stateMachine.onAiSkipped(new AiAnalysisSkippedEvent(queryId, "ai_analysis_enabled=false"));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
        verify(eventPublisher).publishEvent(any(QueryReadyForReviewEvent.class));
    }

    @Test
    void aiSkippedIgnoresQueryNotInPendingAi() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED)));

        stateMachine.onAiSkipped(new AiAnalysisSkippedEvent(queryId, "ai_analysis_enabled=false"));

        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void aiSkippedIgnoresUnknownQuery() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        stateMachine.onAiSkipped(new AiAnalysisSkippedEvent(queryId, "ai_analysis_enabled=false"));

        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void aiCompletedGrantFastPathApprovesOnLowRisk() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.LOW);
        givenActiveGrant(grantView(true, false, false, null, null));
        when(sqlParserService.parse(any())).thenReturn(new SqlParseResult(QueryType.SELECT, false,
                List.of("SELECT * FROM orders"), Set.of("orders")));

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(queryRequestStateService).approveByAccessGrant(queryId, grantId);
        var captor = ArgumentCaptor.forClass(QueryAutoApprovedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().accessGrantId()).isEqualTo(grantId);
        assertThat(captor.getValue().grantApproverEmail()).isEqualTo("approver@x.io");
        assertThat(captor.getValue().matchedPolicyId()).isNull();
        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void aiSkippedGrantFastPathApprovesWithoutRiskSignal() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.LOW);
        givenActiveGrant(grantView(true, false, false, null, null));

        stateMachine.onAiSkipped(new AiAnalysisSkippedEvent(queryId, "ai_analysis_enabled=false"));

        verify(queryRequestStateService).approveByAccessGrant(queryId, grantId);
        verify(eventPublisher).publishEvent(any(QueryAutoApprovedEvent.class));
    }

    @Test
    void grantFastPathSuppressedOnHighRisk() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.HIGH);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.HIGH));

        verify(accessGrantLookupService, never()).findActivePreApprovedGrants(any(), any(), any());
        verify(queryRequestStateService, never()).approveByAccessGrant(any(), any());
        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
    }

    @Test
    void grantFastPathSuppressedOnCriticalRisk() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.CRITICAL);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.CRITICAL));

        verify(accessGrantLookupService, never()).findActivePreApprovedGrants(any(), any(), any());
        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
    }

    @Test
    void grantFastPathSuppressedOnActiveAnomaly() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.LOW);
        when(behaviorAnomalyLookupService.hasActiveAnomaly(organizationId, submitterId,
                datasourceId)).thenReturn(true);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(accessGrantLookupService, never()).findActivePreApprovedGrants(any(), any(), any());
        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
    }

    @Test
    void autoRejectPolicyWinsOverGrantFastPath() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.LOW);
        givenPolicyMatch(RoutingAction.AUTO_REJECT, null);
        lenient().when(accessGrantLookupService.findActivePreApprovedGrants(organizationId,
                submitterId, datasourceId)).thenReturn(List.of(grantView(true, false, false, null,
                null)));

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(queryRequestStateService, never()).approveByAccessGrant(any(), any());
        verify(routingDecisionService).applyDecision(eq(queryId), eq(QueryStatus.REJECTED), any(),
                any());
        verify(eventPublisher).publishEvent(any(QueryAutoRejectedEvent.class));
    }

    @Test
    void escalatePolicyWinsOverGrantFastPath() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.LOW);
        givenPolicyMatch(RoutingAction.ESCALATE, 1);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(queryRequestStateService, never()).approveByAccessGrant(any(), any());
        verify(routingDecisionService).applyDecision(eq(queryId), eq(QueryStatus.PENDING_REVIEW),
                any(), any());
    }

    @Test
    void grantFastPathSkipsCapabilityMismatch() {
        givenPendingAiQuery(QueryType.UPDATE);
        givenPlan(false, true, RiskLevel.LOW);
        givenActiveGrant(grantView(true, false, false, null, null));
        when(sqlParserService.parse(any())).thenReturn(new SqlParseResult(QueryType.UPDATE, false,
                List.of("UPDATE orders SET x=1"), Set.of("orders")));

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(queryRequestStateService, never()).approveByAccessGrant(any(), any());
        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
    }

    @Test
    void grantFastPathSkipsTableOutsideScope() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.LOW);
        givenActiveGrant(grantView(true, false, false, List.of("public"), List.of("orders")));
        when(sqlParserService.parse(any())).thenReturn(new SqlParseResult(QueryType.SELECT, false,
                List.of("SELECT * FROM payroll"), Set.of("payroll")));

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(queryRequestStateService, never()).approveByAccessGrant(any(), any());
        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
    }

    @Test
    void grantFastPathFailsClosedOnParseFailure() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.LOW);
        givenActiveGrant(grantView(true, false, false, null, List.of("orders")));
        when(sqlParserService.parse(any())).thenThrow(new RuntimeException("boom"));

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(queryRequestStateService, never()).approveByAccessGrant(any(), any());
        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
    }

    @Test
    void grantFastPathFallsThroughWhenNoGrant() {
        givenPendingAiQuery(QueryType.SELECT);
        givenPlan(false, true, RiskLevel.LOW);

        stateMachine.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId,
                RiskLevel.LOW));

        verify(accessGrantLookupService).findActivePreApprovedGrants(organizationId, submitterId,
                datasourceId);
        verify(queryRequestStateService, never()).approveByAccessGrant(any(), any());
        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
    }

    @Test
    void aiFailedNeverConsultsGrantLookup() {
        givenPendingAiQuery(QueryType.SELECT);

        stateMachine.onAiFailed(new AiAnalysisFailedEvent(queryId, "provider down"));

        verify(accessGrantLookupService, never()).findActivePreApprovedGrants(any(), any(), any());
        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
    }

    private void givenActiveGrant(AccessGrantView grant) {
        when(accessGrantLookupService.findActivePreApprovedGrants(organizationId, submitterId,
                datasourceId)).thenReturn(List.of(grant));
    }

    private AccessGrantView grantView(boolean canRead, boolean canWrite, boolean canDdl,
                                      List<String> allowedSchemas, List<String> allowedTables) {
        return new AccessGrantView(grantId, organizationId, submitterId, datasourceId,
                canRead, canWrite, canDdl, allowedSchemas, allowedTables,
                AccessGrantStatus.APPROVED, Instant.now().plusSeconds(3600),
                UUID.randomUUID(), "approver@x.io", Instant.now());
    }

    private void givenPendingAiQuery(QueryType type) {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(new QueryRequestSnapshot(queryId, datasourceId,
                        organizationId, submitterId, "SELECT 1", type, false,
                        QueryStatus.PENDING_AI, null, "203.0.113.7", "curl/8.4.0", true)));
    }

    private void givenPlan(boolean autoApproveReads, boolean requiresHumanApproval,
                           RiskLevel ignored) {
        when(reviewPlanLookupService.findForDatasource(eq(datasourceId)))
                .thenReturn(Optional.of(new ReviewPlanSnapshot(
                        UUID.randomUUID(), organizationId, true, requiresHumanApproval, 1,
                        autoApproveReads, 1,
                        List.of(new ApproverRule(null, UserRoleType.REVIEWER, 1)),
                        List.of())));
    }

    private void givenPolicyMatch(RoutingAction action, Integer requiredApprovals) {
        when(routingPolicyEngine.evaluate(eq(organizationId), eq(datasourceId), any()))
                .thenReturn(Optional.of(new RoutingMatch(policyId, "P", action, requiredApprovals,
                        "matched")));
    }

    private QueryRequestSnapshot snapshot(QueryStatus status) {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", QueryType.SELECT, false, status, null, "203.0.113.7", "curl/8.4.0", true);
    }
}
