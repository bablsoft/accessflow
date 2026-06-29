package com.bablsoft.accessflow.lifecycle.events;

import java.util.UUID;

/**
 * Published once erasure scope detection completes and the request advances to PENDING_REVIEW.
 * Consumed by the notifications/realtime modules to alert reviewers.
 */
public record ErasureScopeAnalyzedEvent(UUID requestId, UUID organizationId, long estimatedRows) {
}
