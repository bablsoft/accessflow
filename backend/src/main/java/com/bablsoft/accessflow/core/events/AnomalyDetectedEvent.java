package com.bablsoft.accessflow.core.events;

import java.util.UUID;

/**
 * Published by the AI module when behavioural anomaly detection (UBA, AF-383) persists a new
 * {@code behavior_anomaly} row. Consumed by the realtime module (WebSocket {@code anomaly.detected}
 * fan-out to admins + the subject user) and the notifications module (admin fanout mirroring
 * {@code AI_HIGH_RISK}). Carries only ids + headline signal; consumers re-read full detail via
 * {@code ai.api.BehaviorAnomalyLookupService} so this event stays free of {@code ai.api} types and
 * {@code core} keeps no dependency on the {@code ai} module.
 */
public record AnomalyDetectedEvent(UUID anomalyId, UUID organizationId, UUID userId,
                                   UUID datasourceId, String feature, double score) {
}
