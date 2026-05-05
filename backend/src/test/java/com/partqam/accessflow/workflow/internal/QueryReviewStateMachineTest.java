package com.partqam.accessflow.workflow.internal;

import com.partqam.accessflow.core.api.ApproverRule;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestSnapshot;
import com.partqam.accessflow.core.api.QueryRequestStateService;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.ReviewPlanLookupService;
import com.partqam.accessflow.core.api.ReviewPlanSnapshot;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.events.AiAnalysisCompletedEvent;
import com.partqam.accessflow.core.events.AiAnalysisFailedEvent;
import com.partqam.accessflow.workflow.events.QueryAutoApprovedEvent;
import com.partqam.accessflow.workflow.events.QueryReadyForReviewEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryReviewStateMachineTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock ReviewPlanLookupService reviewPlanLookupService;
    @Mock QueryRequestStateService queryRequestStateService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks QueryReviewStateMachine stateMachine;

    private final UUID queryId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID aiAnalysisId = UUID.randomUUID();

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

    @Test
    void aiFailedAlwaysTransitionsToPendingReview() {
        givenPendingAiQuery(QueryType.SELECT);

        stateMachine.onAiFailed(new AiAnalysisFailedEvent(queryId, "provider error"));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
        verify(eventPublisher).publishEvent(any(QueryReadyForReviewEvent.class));
        // Plan is never consulted on failure path
        verify(reviewPlanLookupService, never()).findForDatasource(any());
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

    private void givenPendingAiQuery(QueryType type) {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(new QueryRequestSnapshot(queryId, datasourceId,
                        organizationId, submitterId, "SELECT 1", type, QueryStatus.PENDING_AI)));
    }

    private void givenPlan(boolean autoApproveReads, boolean requiresHumanApproval,
                           RiskLevel ignored) {
        when(reviewPlanLookupService.findForDatasource(eq(datasourceId)))
                .thenReturn(Optional.of(new ReviewPlanSnapshot(
                        UUID.randomUUID(), organizationId, true, requiresHumanApproval, 1,
                        autoApproveReads, 1,
                        List.of(new ApproverRule(null, UserRoleType.REVIEWER, 1)))));
    }

    private QueryRequestSnapshot snapshot(QueryStatus status) {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", QueryType.SELECT, status);
    }
}
