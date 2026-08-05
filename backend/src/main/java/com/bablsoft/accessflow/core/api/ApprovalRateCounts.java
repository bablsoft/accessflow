package com.bablsoft.accessflow.core.api;

/**
 * Decided / approved counts over the human-decided training population (issue AF-645), used by the
 * feature extractor for Laplace-smoothed approval-rate features ({@code (approved + 1) /
 * (decided + 2)} — the smoothing itself happens in the {@code ai} module). {@code approved} counts
 * queries that reached {@code APPROVED} / {@code EXECUTED}; {@code decided} additionally includes
 * {@code REJECTED} and {@code TIMED_OUT}.
 */
public record ApprovalRateCounts(long decided, long approved) {
}
