package com.bablsoft.accessflow.serviceaccounts.api;

/**
 * An API-key-authenticated identity exceeded its per-minute or per-day request cap (#873) — 429.
 * {@code retryAfterSeconds} is the time left in the exhausted fixed window, so it doubles as the
 * {@code Retry-After} header value.
 */
public final class ServiceAccountRateLimitExceededException extends RuntimeException {

    private final int limit;
    private final long retryAfterSeconds;
    private final String window;

    public ServiceAccountRateLimitExceededException(int limit, long retryAfterSeconds, String window) {
        super("Service account rate limit exceeded: " + limit + " requests per " + window);
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
        this.window = window;
    }

    public int limit() {
        return limit;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** {@code "minute"} or {@code "day"} — which window was exhausted. */
    public String window() {
        return window;
    }
}
