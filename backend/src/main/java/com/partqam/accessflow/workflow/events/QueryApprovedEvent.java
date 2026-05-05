package com.partqam.accessflow.workflow.events;

import java.util.UUID;

/**
 * Published when a human reviewer's approval brings a query to terminal {@code APPROVED} state
 * (final stage threshold met). The audit and notifications modules are the planned consumers.
 */
public record QueryApprovedEvent(UUID queryRequestId, UUID reviewerId) {
}
