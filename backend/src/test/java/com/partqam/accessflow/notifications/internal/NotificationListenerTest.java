package com.partqam.accessflow.notifications.internal;

import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.events.AiAnalysisCompletedEvent;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.workflow.events.QueryApprovedEvent;
import com.partqam.accessflow.workflow.events.QueryAutoApprovedEvent;
import com.partqam.accessflow.workflow.events.QueryReadyForReviewEvent;
import com.partqam.accessflow.workflow.events.QueryRejectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class NotificationListenerTest {

    private NotificationDispatcher dispatcher;
    private NotificationListener listener;

    @BeforeEach
    void setUp() {
        dispatcher = mock(NotificationDispatcher.class);
        listener = new NotificationListener(dispatcher);
    }

    @Test
    void readyForReviewDispatchesQuerySubmitted() {
        var id = UUID.randomUUID();
        listener.onQueryReadyForReview(new QueryReadyForReviewEvent(id));
        verify(dispatcher).dispatch(eq(NotificationEventType.QUERY_SUBMITTED), eq(id),
                isNull(), isNull());
    }

    @Test
    void approvedDispatchesQueryApprovedWithReviewer() {
        var id = UUID.randomUUID();
        var reviewer = UUID.randomUUID();
        listener.onQueryApproved(new QueryApprovedEvent(id, reviewer));
        verify(dispatcher).dispatch(eq(NotificationEventType.QUERY_APPROVED), eq(id),
                eq(reviewer), isNull());
    }

    @Test
    void autoApprovedDispatchesQueryApprovedWithoutReviewer() {
        var id = UUID.randomUUID();
        listener.onQueryAutoApproved(new QueryAutoApprovedEvent(id));
        verify(dispatcher).dispatch(eq(NotificationEventType.QUERY_APPROVED), eq(id),
                isNull(), isNull());
    }

    @Test
    void rejectedDispatchesQueryRejectedWithReviewer() {
        var id = UUID.randomUUID();
        var reviewer = UUID.randomUUID();
        listener.onQueryRejected(new QueryRejectedEvent(id, reviewer));
        verify(dispatcher).dispatch(eq(NotificationEventType.QUERY_REJECTED), eq(id),
                eq(reviewer), isNull());
    }

    @Test
    void aiCompletedFiresOnlyForCriticalRisk() {
        var id = UUID.randomUUID();
        listener.onAiCompleted(new AiAnalysisCompletedEvent(id, UUID.randomUUID(), RiskLevel.LOW));
        listener.onAiCompleted(new AiAnalysisCompletedEvent(id, UUID.randomUUID(), RiskLevel.MEDIUM));
        listener.onAiCompleted(new AiAnalysisCompletedEvent(id, UUID.randomUUID(), RiskLevel.HIGH));
        verify(dispatcher, never()).dispatch(any(), any(), any(), any());

        listener.onAiCompleted(new AiAnalysisCompletedEvent(id, UUID.randomUUID(), RiskLevel.CRITICAL));
        verify(dispatcher).dispatch(eq(NotificationEventType.AI_HIGH_RISK), eq(id),
                isNull(), isNull());
    }

    @Test
    void dispatchExceptionIsSwallowed() {
        var id = UUID.randomUUID();
        doThrow(new RuntimeException("boom"))
                .when(dispatcher).dispatch(any(), any(), any(), any());

        // None of these should propagate.
        listener.onQueryReadyForReview(new QueryReadyForReviewEvent(id));
        listener.onQueryApproved(new QueryApprovedEvent(id, UUID.randomUUID()));
        listener.onQueryAutoApproved(new QueryAutoApprovedEvent(id));
        listener.onQueryRejected(new QueryRejectedEvent(id, UUID.randomUUID()));
        listener.onAiCompleted(new AiAnalysisCompletedEvent(id, UUID.randomUUID(),
                RiskLevel.CRITICAL));
    }
}
