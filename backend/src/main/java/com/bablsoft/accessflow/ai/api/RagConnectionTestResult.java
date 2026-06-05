package com.bablsoft.accessflow.ai.api;

/**
 * Outcome of a RAG connectivity smoke test (AF-336): embed a probe and run a similarity search to
 * verify the embedding model and vector store are reachable. {@code embeddingDimensions} is the
 * detected embedding vector length (used to confirm it matches the pgvector column for PGVECTOR);
 * {@code null} when the test failed before embedding.
 */
public record RagConnectionTestResult(
        boolean ok,
        String detail,
        Integer embeddingDimensions) {

    public static RagConnectionTestResult ok(String detail, int embeddingDimensions) {
        return new RagConnectionTestResult(true, detail, embeddingDimensions);
    }

    public static RagConnectionTestResult error(String detail) {
        return new RagConnectionTestResult(false, detail, null);
    }
}
