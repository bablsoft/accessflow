package com.bablsoft.accessflow.proxy.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplicaHealthRegistryTest {

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID endpointId = UUID.randomUUID();
    private final Instant base = Instant.parse("2026-07-16T12:00:00Z");
    private Clock clock;
    private ReplicaHealthRegistry registry;

    @BeforeEach
    void setUp() {
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(base);
        registry = new ReplicaHealthRegistry(clock,
                new ProxyReplicaProperties(null, null, Duration.ofSeconds(30)));
    }

    @Test
    void unknownEndpointIsHealthyCandidate() {
        assertThat(registry.isCandidate(datasourceId, endpointId)).isTrue();
        assertThat(registry.isHealthy(datasourceId, endpointId)).isTrue();
    }

    @Test
    void failureOpensCircuitForCooldown() {
        registry.recordFailure(datasourceId, endpointId);

        assertThat(registry.isCandidate(datasourceId, endpointId)).isFalse();
        assertThat(registry.isHealthy(datasourceId, endpointId)).isFalse();
    }

    @Test
    void cooldownElapsedAdmitsSingleHalfOpenTrial() {
        registry.recordFailure(datasourceId, endpointId);
        when(clock.instant()).thenReturn(base.plusSeconds(31));

        assertThat(registry.isCandidate(datasourceId, endpointId)).isTrue();
        // Second caller is turned away while the trial is outstanding.
        assertThat(registry.isCandidate(datasourceId, endpointId)).isFalse();
        assertThat(registry.isHealthy(datasourceId, endpointId)).isFalse();
    }

    @Test
    void successAfterHalfOpenTrialRestoresHealthy() {
        registry.recordFailure(datasourceId, endpointId);
        when(clock.instant()).thenReturn(base.plusSeconds(31));
        assertThat(registry.isCandidate(datasourceId, endpointId)).isTrue();

        registry.recordSuccess(datasourceId, endpointId);

        assertThat(registry.isHealthy(datasourceId, endpointId)).isTrue();
        assertThat(registry.isCandidate(datasourceId, endpointId)).isTrue();
    }

    @Test
    void failureAfterHalfOpenTrialRearmsCooldown() {
        registry.recordFailure(datasourceId, endpointId);
        when(clock.instant()).thenReturn(base.plusSeconds(31));
        assertThat(registry.isCandidate(datasourceId, endpointId)).isTrue();

        registry.recordFailure(datasourceId, endpointId);

        assertThat(registry.isCandidate(datasourceId, endpointId)).isFalse();
        when(clock.instant()).thenReturn(base.plusSeconds(62));
        assertThat(registry.isCandidate(datasourceId, endpointId)).isTrue();
    }

    @Test
    void evictDropsAllEndpointStateForDatasource() {
        var otherDatasource = UUID.randomUUID();
        registry.recordFailure(datasourceId, endpointId);
        registry.recordFailure(otherDatasource, endpointId);

        registry.evict(datasourceId);

        assertThat(registry.isHealthy(datasourceId, endpointId)).isTrue();
        assertThat(registry.isHealthy(otherDatasource, endpointId)).isFalse();
    }
}
