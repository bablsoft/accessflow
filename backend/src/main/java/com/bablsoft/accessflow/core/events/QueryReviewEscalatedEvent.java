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
 * <p>Carries the request id and nothing else, like {@code ApiReviewEscalatedEvent}: the
 * notification layer already loads the request and its plan to resolve recipients, so any window
 * duplicated here would be a second copy of the same fact that nothing reads.
 */
public record QueryReviewEscalatedEvent(UUID queryRequestId) {
}
