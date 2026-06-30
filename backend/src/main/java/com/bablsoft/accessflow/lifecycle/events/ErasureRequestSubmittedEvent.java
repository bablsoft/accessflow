package com.bablsoft.accessflow.lifecycle.events;

import java.util.UUID;

/** Published when a right-to-erasure request is submitted; triggers async AI scope detection. */
public record ErasureRequestSubmittedEvent(UUID requestId, UUID organizationId, UUID datasourceId) {
}
