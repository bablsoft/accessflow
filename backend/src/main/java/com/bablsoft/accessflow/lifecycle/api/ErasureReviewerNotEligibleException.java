package com.bablsoft.accessflow.lifecycle.api;

import java.util.UUID;

/**
 * Thrown when a caller attempts to decide an erasure request they are not an eligible reviewer for
 * per the datasource's review plan / scoped-reviewer set (and is not an ADMIN backstop). Mapped to
 * HTTP 403 by the lifecycle web layer.
 */
public final class ErasureReviewerNotEligibleException extends LifecycleException {

    public ErasureReviewerNotEligibleException(UUID userId, UUID requestId) {
        super("User " + userId + " is not an eligible reviewer for erasure request " + requestId);
    }
}
