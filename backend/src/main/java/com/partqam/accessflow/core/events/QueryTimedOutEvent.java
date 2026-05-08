package com.partqam.accessflow.core.events;

import java.util.UUID;

/**
 * Published by {@code QueryRequestStateService.markTimedOut} when a query that has been
 * {@code PENDING_REVIEW} longer than its plan's {@code approval_timeout_hours} is auto-transitioned
 * to {@code REJECTED}.
 *
 * <p>System-driven counterpart to {@code QueryRejectedEvent} (workflow module): no reviewer is
 * attributed. Audit and notification consumers use this to render the "auto-rejected due to
 * timeout" path differently from a manual rejection.
 */
public record QueryTimedOutEvent(UUID queryRequestId, int approvalTimeoutHours) {
}
