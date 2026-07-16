package com.bablsoft.accessflow.proxy.internal;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-node circuit breaker over read-replica endpoints (AF-457). An endpoint with no recorded
 * failure is HEALTHY; a failure marks it DOWN for {@code accessflow.proxy.replica.cooldown},
 * during which the read load-balancer skips it. Once the cooldown elapses, exactly one caller is
 * admitted as a HALF_OPEN trial — success restores HEALTHY, failure re-arms the cooldown.
 *
 * <p>State is deliberately in-memory and per JVM: it mirrors this node's HikariCP pools, which
 * are also per JVM. Each cluster node discovers replica outages (and recoveries) independently.
 */
@Component
class ReplicaHealthRegistry {

    private final Clock clock;
    private final ProxyReplicaProperties properties;
    private final ConcurrentMap<Key, State> states = new ConcurrentHashMap<>();

    ReplicaHealthRegistry(Clock proxyClock, ProxyReplicaProperties properties) {
        this.clock = proxyClock;
        this.properties = properties;
    }

    private record Key(UUID datasourceId, UUID endpointId) {
    }

    private record State(boolean halfOpen, Instant downUntil) {
    }

    /**
     * Whether the load-balancer may try this endpoint now. HEALTHY endpoints always qualify; a
     * DOWN endpoint qualifies only once its cooldown has elapsed, and then admits a single
     * half-open trial (concurrent callers are turned away until the trial resolves).
     */
    boolean isCandidate(UUID datasourceId, UUID endpointId) {
        var key = new Key(datasourceId, endpointId);
        var state = states.get(key);
        if (state == null) {
            return true;
        }
        if (state.halfOpen()) {
            return false;
        }
        if (clock.instant().isBefore(state.downUntil())) {
            return false;
        }
        return states.replace(key, state, new State(true, state.downUntil()));
    }

    /** Whether the endpoint is currently in rotation (no open circuit), for health reporting. */
    boolean isHealthy(UUID datasourceId, UUID endpointId) {
        return states.get(new Key(datasourceId, endpointId)) == null;
    }

    void recordSuccess(UUID datasourceId, UUID endpointId) {
        states.remove(new Key(datasourceId, endpointId));
    }

    void recordFailure(UUID datasourceId, UUID endpointId) {
        states.put(new Key(datasourceId, endpointId),
                new State(false, clock.instant().plus(properties.cooldown())));
    }

    /** Drops all endpoint state for a datasource (pool eviction / config change). */
    void evict(UUID datasourceId) {
        states.keySet().removeIf(key -> key.datasourceId().equals(datasourceId));
    }
}
