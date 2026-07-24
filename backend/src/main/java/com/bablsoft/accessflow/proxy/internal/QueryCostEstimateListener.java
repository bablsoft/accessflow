package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.events.QuerySubmittedEvent;
import com.bablsoft.accessflow.proxy.api.QueryCostEstimateService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Kicks off the pre-flight cost estimate (issue AF-624) asynchronously after submission — the
 * proxy-side analogue of the AI module's {@code AiAnalysisListener}. Runs unconditionally
 * (regardless of {@code ai_analysis_enabled}); the AI analyzer independently requests the same
 * estimate for its prompt, and the service's insert-once persistence makes the race harmless.
 */
@Component
@RequiredArgsConstructor
class QueryCostEstimateListener {

    private final QueryCostEstimateService queryCostEstimateService;

    @ApplicationModuleListener
    void onSubmitted(QuerySubmittedEvent event) {
        queryCostEstimateService.estimateSubmittedQuery(event.queryRequestId());
    }
}
