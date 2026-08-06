package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.ApprovalPredictionService;
import com.bablsoft.accessflow.core.events.QueryEstimateCompletedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ApprovalPredictionListenerTest {

    @Mock ApprovalPredictionService approvalPredictionService;

    @InjectMocks ApprovalPredictionListener listener;

    @Test
    void readyForReviewTriggersAPrediction() {
        var queryRequestId = UUID.randomUUID();

        listener.onReadyForReview(new QueryReadyForReviewEvent(queryRequestId,
                UUID.randomUUID(), "policy matched", 2));

        verify(approvalPredictionService).predictForQuery(queryRequestId);
        verifyNoMoreInteractions(approvalPredictionService);
    }

    @Test
    void estimateCompletedTriggersALateEstimateRefresh() {
        var queryRequestId = UUID.randomUUID();

        listener.onEstimateCompleted(
                new QueryEstimateCompletedEvent(queryRequestId, UUID.randomUUID(), true));

        verify(approvalPredictionService).refreshForLateEstimate(queryRequestId);
        verifyNoMoreInteractions(approvalPredictionService);
    }
}
