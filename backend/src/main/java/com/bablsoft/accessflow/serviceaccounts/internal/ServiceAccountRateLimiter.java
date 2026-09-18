package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountRateLimitExceededException;

import java.util.UUID;

/**
 * Per-identity request cap for API-key-authenticated traffic (#873). {@code public} only because
 * {@code internal.web} and {@code internal.config} are sibling packages — still module-private.
 */
public interface ServiceAccountRateLimiter {

    /**
     * Counts one request for {@code userId} and throws when the identity is over its per-minute or
     * per-day cap. Returns normally when the limits are disabled or the limiter cannot reach its
     * backing stores (fail-open).
     *
     * @throws ServiceAccountRateLimitExceededException when a window is exhausted
     */
    void enforce(UUID userId);
}
