package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * A periodic reminder is due for a query still awaiting review (#622), on its plan's
 * {@code nudge_interval_hours} cadence. {@code last_nudged_at} is stamped in the same transaction,
 * so the cadence survives restarts and cannot double-fire across replicas.
 */
public record QueryReviewNudgedEvent(UUID queryRequestId) {
}
