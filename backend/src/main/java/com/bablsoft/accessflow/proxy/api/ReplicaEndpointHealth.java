package com.bablsoft.accessflow.proxy.api;

import java.util.UUID;

/**
 * Health of one read-replica endpoint as surfaced on the admin datasource-health snapshot
 * (AF-457). {@code healthy} reflects this node's circuit-breaker state (a DOWN endpoint is being
 * skipped by the read load-balancer until its cooldown elapses). Pool gauges are {@code null}
 * when the endpoint's pool has not been created on this node yet.
 */
public record ReplicaEndpointHealth(
        UUID endpointId,
        String label,
        boolean healthy,
        Integer poolActive,
        Integer poolTotal) {
}
