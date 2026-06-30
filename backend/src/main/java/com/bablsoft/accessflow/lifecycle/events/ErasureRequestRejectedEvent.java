package com.bablsoft.accessflow.lifecycle.events;

import java.util.UUID;

/** Published when a deletion request is rejected. */
public record ErasureRequestRejectedEvent(UUID requestId, UUID organizationId, UUID reviewerId) {
}
