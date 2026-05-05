package com.partqam.accessflow.workflow.events;

import java.util.UUID;

/**
 * Published when a reviewer rejects a query (any single rejection at any stage transitions the
 * query to terminal {@code REJECTED}).
 */
public record QueryRejectedEvent(UUID queryRequestId, UUID reviewerId) {
}
