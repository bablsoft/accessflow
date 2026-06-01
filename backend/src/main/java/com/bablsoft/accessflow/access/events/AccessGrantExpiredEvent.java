package com.bablsoft.accessflow.access.events;

import java.util.UUID;

/**
 * Published when {@code AccessGrantExpiryJob} revokes a grant whose {@code expires_at} has passed.
 * {@code permissionId} may be null if the underlying permission was already gone.
 */
public record AccessGrantExpiredEvent(UUID accessRequestId, UUID requesterId, UUID permissionId) {
}
