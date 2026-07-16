package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.proxy.api.PoolInitializationException;
import com.bablsoft.accessflow.proxy.api.ReplicaEndpointRef;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingDataSourceResolverTest {

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID endpointA = UUID.randomUUID();
    private final UUID endpointB = UUID.randomUUID();
    private final DatasourceConnectionPoolManager poolManager = mock(DatasourceConnectionPoolManager.class);
    private final DatasourceLookupService lookupService = mock(DatasourceLookupService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final StaticMessageSource messageSource = new StaticMessageSource();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObservationRegistry observationRegistry = ObservationRegistry.create();
    private ReplicaHealthRegistry healthRegistry;
    private RoutingDataSourceResolver resolver;
    private DataSource primaryPool;
    private DataSource replicaPoolA;
    private DataSource replicaPoolB;
    private Connection primaryConnection;
    private Connection replicaConnectionA;
    private Connection replicaConnectionB;

    @BeforeEach
    void setUp() throws SQLException {
        messageSource.addMessage("error.datasource_unavailable_not_found",
                java.util.Locale.ENGLISH, "Datasource not found");
        primaryPool = mock(DataSource.class);
        replicaPoolA = mock(DataSource.class);
        replicaPoolB = mock(DataSource.class);
        primaryConnection = mock(Connection.class);
        replicaConnectionA = mock(Connection.class);
        replicaConnectionB = mock(Connection.class);
        when(primaryPool.getConnection()).thenReturn(primaryConnection);
        when(replicaPoolA.getConnection()).thenReturn(replicaConnectionA);
        when(replicaPoolB.getConnection()).thenReturn(replicaConnectionB);
        when(poolManager.resolve(datasourceId)).thenReturn(primaryPool);
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        healthRegistry = new ReplicaHealthRegistry(
                Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC),
                new ProxyReplicaProperties(null, null, Duration.ofSeconds(30)));
        resolver = new RoutingDataSourceResolver(poolManager, healthRegistry, lookupService,
                auditLogService, messageSource, observationRegistry);
    }

    private void givenReplicas(UUID... endpointIds) {
        var refs = new java.util.ArrayList<ReplicaEndpointRef>();
        for (int i = 0; i < endpointIds.length; i++) {
            refs.add(new ReplicaEndpointRef(endpointIds[i], "replica-" + i + ":5432"));
        }
        when(poolManager.replicaEndpoints(datasourceId)).thenReturn(List.copyOf(refs));
    }

    @Test
    void recordsAcquireObservationWithQueryTypeAndSuccessOutcome() throws SQLException {
        givenReplicas();

        resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(meterRegistry.get("accessflow.datasource.acquire")
                .tags("query_type", "SELECT", "outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void nonSelectAlwaysUsesPrimary() throws SQLException {
        var connection = resolver.acquire(datasourceId, QueryType.INSERT);

        assertThat(connection).isSameAs(primaryConnection);
        verify(poolManager, never()).replicaEndpoints(datasourceId);
        verify(auditLogService, never()).record(any());
    }

    @Test
    void selectWithoutReplicaUsesPrimary() throws SQLException {
        givenReplicas();

        var connection = resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(connection).isSameAs(primaryConnection);
        verify(auditLogService, never()).record(any());
    }

    @Test
    void selectWithSingleReplicaUsesReplica() throws SQLException {
        givenReplicas(endpointA);
        when(poolManager.resolveReplica(datasourceId, endpointA)).thenReturn(replicaPoolA);

        var connection = resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(connection).isSameAs(replicaConnectionA);
        verify(poolManager, never()).resolve(datasourceId);
        verify(auditLogService, never()).record(any());
    }

    @Test
    void selectsRoundRobinAcrossHealthyReplicas() throws SQLException {
        givenReplicas(endpointA, endpointB);
        when(poolManager.resolveReplica(datasourceId, endpointA)).thenReturn(replicaPoolA);
        when(poolManager.resolveReplica(datasourceId, endpointB)).thenReturn(replicaPoolB);

        var first = resolver.acquire(datasourceId, QueryType.SELECT);
        var second = resolver.acquire(datasourceId, QueryType.SELECT);
        var third = resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(first).isSameAs(replicaConnectionA);
        assertThat(second).isSameAs(replicaConnectionB);
        assertThat(third).isSameAs(replicaConnectionA);
        verify(auditLogService, never()).record(any());
    }

    @Test
    void failedEndpointIsSkippedAndNextCandidateServes() throws SQLException {
        givenReplicas(endpointA, endpointB);
        when(poolManager.resolveReplica(datasourceId, endpointA)).thenReturn(replicaPoolA);
        when(poolManager.resolveReplica(datasourceId, endpointB)).thenReturn(replicaPoolB);
        when(replicaPoolA.getConnection()).thenThrow(new SQLException("conn refused"));

        var first = resolver.acquire(datasourceId, QueryType.SELECT);
        // Endpoint A is now DOWN — the next SELECT must not try it again.
        var second = resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(first).isSameAs(replicaConnectionB);
        assertThat(second).isSameAs(replicaConnectionB);
        verify(replicaPoolA, times(1)).getConnection();
        // A sibling replica served the read: no full exhaustion, no audit.
        verify(auditLogService, never()).record(any());
    }

    @Test
    void selectFallsBackToPrimaryAndAuditsOnceWhenAllReplicasFail() throws SQLException {
        givenReplicas(endpointA, endpointB);
        when(poolManager.resolveReplica(datasourceId, endpointA)).thenReturn(replicaPoolA);
        when(poolManager.resolveReplica(datasourceId, endpointB)).thenReturn(replicaPoolB);
        when(replicaPoolA.getConnection()).thenThrow(new SQLException("a gone"));
        when(replicaPoolB.getConnection()).thenThrow(new SQLException("b gone"));
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(activeDescriptor()));

        var connection = resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(connection).isSameAs(primaryConnection);
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService, times(1)).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.DATASOURCE_REPLICA_FALLBACK);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.DATASOURCE);
        assertThat(entry.resourceId()).isEqualTo(datasourceId);
        assertThat(entry.organizationId()).isEqualTo(organizationId);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.metadata())
                .containsEntry("query_type", "SELECT")
                .containsEntry("replica_count", 2)
                .containsKey("error")
                .containsKey("tried_endpoints");
    }

    @Test
    void openCircuitsServePrimaryWithoutAdditionalAudit() throws SQLException {
        givenReplicas(endpointA);
        when(poolManager.resolveReplica(datasourceId, endpointA)).thenReturn(replicaPoolA);
        when(replicaPoolA.getConnection()).thenThrow(new SQLException("gone"));
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(activeDescriptor()));

        resolver.acquire(datasourceId, QueryType.SELECT); // fails + audits, opens circuit
        var second = resolver.acquire(datasourceId, QueryType.SELECT); // circuit open: no attempt

        assertThat(second).isSameAs(primaryConnection);
        verify(replicaPoolA, times(1)).getConnection();
        verify(auditLogService, times(1)).record(any());
    }

    @Test
    void selectFallsBackToPrimaryAndAuditsWhenReplicaPoolInitFails() throws SQLException {
        givenReplicas(endpointA);
        when(poolManager.resolveReplica(datasourceId, endpointA)).thenThrow(
                new PoolInitializationException("pool init failed", new RuntimeException()));
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(activeDescriptor()));

        var connection = resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(connection).isSameAs(primaryConnection);
        verify(auditLogService, times(1)).record(any());
    }

    @Test
    void primaryFailurePropagatesAfterReplicaFallback() throws SQLException {
        givenReplicas(endpointA);
        when(poolManager.resolveReplica(datasourceId, endpointA)).thenReturn(replicaPoolA);
        when(replicaPoolA.getConnection()).thenThrow(new SQLException("replica gone"));
        when(primaryPool.getConnection()).thenThrow(new SQLException("primary gone"));
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(activeDescriptor()));

        assertThatThrownBy(() -> resolver.acquire(datasourceId, QueryType.SELECT))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("primary gone");
        verify(auditLogService, times(1)).record(any());
    }

    @Test
    void auditFailureIsSwallowed() throws SQLException {
        givenReplicas(endpointA);
        when(poolManager.resolveReplica(datasourceId, endpointA)).thenReturn(replicaPoolA);
        when(replicaPoolA.getConnection()).thenThrow(new SQLException("replica gone"));
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(activeDescriptor()));
        when(auditLogService.record(any())).thenThrow(new RuntimeException("audit broken"));

        var connection = resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(connection).isSameAs(primaryConnection);
    }

    @Test
    void successfulConnectionRecordsSuccessAndClosesHalfOpenTrial() throws SQLException {
        givenReplicas(endpointA);
        when(poolManager.resolveReplica(datasourceId, endpointA)).thenReturn(replicaPoolA);
        healthRegistry.recordFailure(datasourceId, endpointA);
        // Cooldown is 30s on a fixed clock — not yet a candidate.
        assertThat(healthRegistry.isCandidate(datasourceId, endpointA)).isFalse();
        healthRegistry.recordSuccess(datasourceId, endpointA); // simulate prober recovery

        var connection = resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(connection).isSameAs(replicaConnectionA);
        assertThat(healthRegistry.isHealthy(datasourceId, endpointA)).isTrue();
    }

    private DatasourceConnectionDescriptor activeDescriptor() {
        return new DatasourceConnectionDescriptor(datasourceId, organizationId,
                DbType.POSTGRESQL, "h", 5432, "db", "u", "ENC", SslMode.DISABLE, 10, 1000,
                false, null, false, null, null, null,
                "jdbc:postgresql://replica:5432/db", "ru", "ENC(rpw)", true);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
