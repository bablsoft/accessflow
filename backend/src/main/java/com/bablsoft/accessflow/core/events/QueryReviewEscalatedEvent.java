package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * A query has sat in {@code PENDING_REVIEW} past its plan's {@code escalation_after_hours} (#622).
 *
 * <p>Published once per request — {@code escalated_at} is stamped in the same transaction, so a
 * restart, a retry, or a second replica cannot produce a duplicate.
 *
 * <p>Advisory only: escalation notifies, it never widens who may approve.
 *
 * @param escalationAfterHours the plan's configured window, for the notification copy
 */
public record QueryReviewEscalatedEvent(UUID queryRequestId, int escalationAfterHours) {
}
