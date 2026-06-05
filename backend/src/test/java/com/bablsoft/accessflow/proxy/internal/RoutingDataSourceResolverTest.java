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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
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
    private final DatasourceConnectionPoolManager poolManager = mock(DatasourceConnectionPoolManager.class);
    private final DatasourceLookupService lookupService = mock(DatasourceLookupService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final StaticMessageSource messageSource = new StaticMessageSource();
    private RoutingDataSourceResolver resolver;
    private DataSource primaryPool;
    private DataSource replicaPool;
    private Connection primaryConnection;
    private Connection replicaConnection;

    @BeforeEach
    void setUp() throws SQLException {
        messageSource.addMessage("error.datasource_unavailable_not_found",
                java.util.Locale.ENGLISH, "Datasource not found");
        primaryPool = mock(DataSource.class);
        replicaPool = mock(DataSource.class);
        primaryConnection = mock(Connection.class);
        replicaConnection = mock(Connection.class);
        when(primaryPool.getConnection()).thenReturn(primaryConnection);
        when(replicaPool.getConnection()).thenReturn(replicaConnection);
        when(poolManager.resolve(datasourceId)).thenReturn(primaryPool);
        resolver = new RoutingDataSourceResolver(poolManager, lookupService, auditLogService,
                messageSource);
    }

    @Test
    void nonSelectAlwaysUsesPrimary() throws SQLException {
        var connection = resolver.acquire(datasourceId, QueryType.INSERT);

        assertThat(connection).isSameAs(primaryConnection);
        verify(poolManager, never()).resolveReplica(datasourceId);
        verify(auditLogService, never()).record(any());
    }

    @Test
    void selectWithoutReplicaUsesPrimary() throws SQLException {
        when(poolManager.resolveReplica(datasourceId)).thenReturn(Optional.empty());

        var connection = resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(connection).isSameAs(primaryConnection);
        verify(auditLogService, never()).record(any());
    }

    @Test
    void selectWithReplicaUsesReplica() throws SQLException {
        when(poolManager.resolveReplica(datasourceId)).thenReturn(Optional.of(replicaPool));

        var connection = resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(connection).isSameAs(replicaConnection);
        verify(poolManager, never()).resolve(datasourceId);
        verify(auditLogService, never()).record(any());
    }

    @Test
    void selectFallsBackToPrimaryAndAuditsWhenReplicaConnectionFails() throws SQLException {
        when(poolManager.resolveReplica(datasourceId)).thenReturn(Optional.of(replicaPool));
        when(replicaPool.getConnection()).thenThrow(new SQLException("conn refused"));
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
                .containsEntry("error", "conn refused")
                .containsEntry("query_type", "SELECT");
    }

    @Test
    void selectFallsBackToPrimaryAndAuditsWhenReplicaPoolInitFails() throws SQLException {
        when(poolManager.resolveReplica(datasourceId)).thenThrow(
                new PoolInitializationException("pool init failed", new RuntimeException()));
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(activeDescriptor()));

        var connection = resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(connection).isSameAs(primaryConnection);
        verify(auditLogService, times(1)).record(any());
    }

    @Test
    void primaryFailurePropagatesAfterReplicaFallback() throws SQLException {
        when(poolManager.resolveReplica(datasourceId)).thenReturn(Optional.of(replicaPool));
        when(replicaPool.getConnection()).thenThrow(new SQLException("replica gone"));
        when(primaryPool.getConnection()).thenThrow(new SQLException("primary gone"));
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(activeDescriptor()));

        assertThatThrownBy(() -> resolver.acquire(datasourceId, QueryType.SELECT))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("primary gone");
        verify(auditLogService, times(1)).record(any());
    }

    @Test
    void auditFailureIsSwallowed() throws SQLException {
        when(poolManager.resolveReplica(datasourceId)).thenReturn(Optional.of(replicaPool));
        when(replicaPool.getConnection()).thenThrow(new SQLException("replica gone"));
        when(lookupService.findById(datasourceId)).thenReturn(Optional.of(activeDescriptor()));
        when(auditLogService.record(any())).thenThrow(new RuntimeException("audit broken"));

        var connection = resolver.acquire(datasourceId, QueryType.SELECT);

        assertThat(connection).isSameAs(primaryConnection);
    }

    private DatasourceConnectionDescriptor activeDescriptor() {
        return new DatasourceConnectionDescriptor(datasourceId, organizationId,
                DbType.POSTGRESQL, "h", 5432, "db", "u", "ENC", SslMode.DISABLE, 10, 1000,
                false, null, false, null, null,
                "jdbc:postgresql://replica:5432/db", "ru", "ENC(rpw)", true);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
