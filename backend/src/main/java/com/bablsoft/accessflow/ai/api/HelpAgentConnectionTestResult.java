package com.bablsoft.accessflow.ai.api;

/**
 * Outcome of a help-agent retrieval smoke test (AF-901): embed a probe with the bound configuration's
 * embedding model and run a similarity search to verify the model and vector store are reachable.
 * {@code embeddingDimensions} is the detected embedding vector length — used to confirm it matches
 * the pgvector column — and is {@code null} when the test failed before embedding.
 */
public record HelpAgentConnectionTestResult(
        boolean ok,
        String detail,
        Integer embeddingDimensions) {

    public static HelpAgentConnectionTestResult ok(String detail, int embeddingDimensions) {
        return new HelpAgentConnectionTestResult(true, detail, embeddingDimensions);
    }

    public static HelpAgentConnectionTestResult error(String detail) {
        return new HelpAgentConnectionTestResult(false, detail, null);
    }
}
