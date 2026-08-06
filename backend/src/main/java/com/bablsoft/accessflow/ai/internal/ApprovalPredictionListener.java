package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.ApprovalPredictionService;
import com.bablsoft.accessflow.core.events.QueryEstimateCompletedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Drives approval-outcome prediction off the workflow's own events (issue AF-651), mirroring
 * {@link AiAnalysisListener}.
 *
 * <p>{@code QueryReadyForReviewEvent} is published exactly when a query transitions into
 * {@code PENDING_REVIEW} and never on an auto path — routing auto-approve/reject, grant coverage and
 * break-glass all bypass it — which is precisely the population that has a human decision to predict.
 *
 * <p>{@code QueryEstimateCompletedEvent} is the second trigger only because the cost estimate is
 * computed in parallel with the AI analysis: on the AI-disabled and AI-failed paths it can land
 * <em>after</em> the query is already in review, and the estimate contributes four features. It
 * fires for every submitted query, so the service's guards (not this listener) decide whether
 * anything is worth re-scoring.
 */
@Component
@RequiredArgsConstructor
class ApprovalPredictionListener {

    private final ApprovalPredictionService approvalPredictionService;

    @ApplicationModuleListener
    void onReadyForReview(QueryReadyForReviewEvent event) {
        approvalPredictionService.predictForQuery(event.queryRequestId());
    }

    @ApplicationModuleListener
    void onEstimateCompleted(QueryEstimateCompletedEvent event) {
        approvalPredictionService.refreshForLateEstimate(event.queryRequestId());
    }
}
