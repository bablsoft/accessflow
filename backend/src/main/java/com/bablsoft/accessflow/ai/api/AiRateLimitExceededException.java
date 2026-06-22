package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when an organization exceeds its per-minute AI analysis request limit
 * ({@code accessflow.ai.rate-limit.requests-per-minute}, AF-55). Extends
 * {@link AiAnalysisException} so the async submitted-query path's existing failure handling routes
 * it to a sentinel {@code CRITICAL} analysis row; the synchronous preview / text-to-SQL paths map it
 * to HTTP 429.
 */
public class AiRateLimitExceededException extends AiAnalysisException {

    private final int limit;
    private final long retryAfterSeconds;

    public AiRateLimitExceededException(int limit, long retryAfterSeconds) {
        super("AI analysis rate limit exceeded: " + limit + " requests per minute");
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int limit() {
        return limit;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
