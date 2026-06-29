package com.bablsoft.accessflow.lifecycle.events;

import java.util.UUID;

/** Published when a deletion request is approved. */
public record ErasureRequestApprovedEvent(UUID requestId, UUID organizationId, UUID reviewerId) {
}
