package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published after a pre-flight cost estimate (issue AF-624) has been computed and persisted for a
 * submitted query — on both the supported and the gracefully-degraded (engine has no plan concept)
 * paths. Consumed by the realtime module to push the {@code query.estimate_complete} WebSocket
 * event so open detail views refetch.
 */
public record QueryEstimateCompletedEvent(UUID queryRequestId, UUID queryEstimateId,
                                          boolean supported) {
}
