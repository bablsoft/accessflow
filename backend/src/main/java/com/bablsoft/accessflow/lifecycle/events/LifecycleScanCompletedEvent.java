package com.bablsoft.accessflow.lifecycle.events;

import java.util.UUID;

/**
 * Published once per {@code RetentionPolicyScanJob} cycle after eligible runs are staged. Consumed by
 * the notifications module to fan out a {@code LIFECYCLE_SCAN_COMPLETED} alert.
 *
 * @param organizationId the org whose policies were scanned (null for a multi-org cycle summary)
 * @param scannedPolicies number of enabled policies scanned
 * @param stagedRuns number of lifecycle runs staged this cycle
 */
public record LifecycleScanCompletedEvent(UUID organizationId, int scannedPolicies, int stagedRuns) {
}
