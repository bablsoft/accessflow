package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.RagConnectionTestResult;

/**
 * RAG connectivity smoke-test result for the admin UI. {@code status} is {@code OK} / {@code ERROR};
 * {@code embeddingDimensions} is the detected embedding vector length (null on failure).
 */
record RagTestResponse(String status, String detail, Integer embeddingDimensions) {

    static RagTestResponse from(RagConnectionTestResult result) {
        return new RagTestResponse(result.ok() ? "OK" : "ERROR", result.detail(),
                result.embeddingDimensions());
    }
}
