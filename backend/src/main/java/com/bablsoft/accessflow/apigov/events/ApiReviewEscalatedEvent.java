package com.bablsoft.accessflow.apigov.events;

import java.util.UUID;

/**
 * A governed API request has sat in {@code PENDING_REVIEW} past its connector plan's
 * {@code escalation_after_hours} (#622). Published once — {@code escalated_at} is stamped in the
 * same transaction. Advisory: escalation never widens who may approve.
 */
public record ApiReviewEscalatedEvent(UUID apiRequestId) {
}
