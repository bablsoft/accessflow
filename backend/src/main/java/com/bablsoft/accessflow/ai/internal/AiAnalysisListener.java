package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiAnalyzerService;
import com.bablsoft.accessflow.core.events.QuerySubmittedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AiAnalysisListener {

    private final AiAnalyzerService aiAnalyzerService;

    @ApplicationModuleListener
    void onSubmitted(QuerySubmittedEvent event) {
        aiAnalyzerService.analyzeSubmittedQuery(event.queryRequestId());
    }
}
