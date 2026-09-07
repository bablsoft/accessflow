package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.HelpAgentConnectionTestResult;

/**
 * Help-agent retrieval smoke-test result for the admin UI. {@code status} is {@code OK} /
 * {@code ERROR}; {@code embeddingDimensions} is the detected embedding vector length (null on failure).
 */
record HelpAgentTestResponse(String status, String detail, Integer embeddingDimensions) {

    static HelpAgentTestResponse from(HelpAgentConnectionTestResult result) {
        return new HelpAgentTestResponse(result.ok() ? "OK" : "ERROR", result.detail(),
                result.embeddingDimensions());
    }
}
