package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.core.api.DatasourceConnectionDescriptor;
import com.partqam.accessflow.core.api.DatasourceLookupService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.proxy.api.DatasourceUnavailableException;
import com.partqam.accessflow.proxy.api.PoolInitializationException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDatasourceConnectionPoolManagerTest {

    @Mock DatasourceLookupService datasourceLookupService;
    @Mock DatasourcePoolFactory poolFactory;

    private DefaultDatasourceConnectionPoolManager manager;

    private final UUID id = UUID.randomUUID();
    private final DatasourceConnectionDescriptor activeDescriptor = new DatasourceConnectionDescriptor(
            id, DbType.POSTGRESQL, "h", 5432, "db", "u", "ENC", SslMode.DISABLE, 10, 1000, true);

    @BeforeEach
    void setUp() {
        manager = new DefaultDatasourceConnectionPoolManager(datasourceLookupService, poolFactory);
    }

    @Test
    void resolveCreatesPoolOnFirstCall() {
        var pool = mock(HikariDataSource.class);
        when(datasourceLookupService.findById(id)).thenReturn(Optional.of(activeDescriptor));
        when(poolFactory.createPool(activeDescriptor)).thenReturn(pool);

        var result = manager.resolve(id);

        assertThat(result).isSameAs(pool);
        verify(poolFactory, times(1)).createPool(activeDescriptor);
    }

    @Test
    void resolveCachesPoolOnSubsequentCalls() {
        var pool = mock(HikariDataSource.class);
        when(datasourceLookupService.findById(id)).thenReturn(Optional.of(activeDescriptor));
        when(poolFactory.createPool(activeDescriptor)).thenReturn(pool);

        var first = manager.resolve(id);
        var second = manager.resolve(id);

        assertThat(second).isSameAs(first);
        verify(poolFactory, times(1)).createPool(any());
    }

    @Test
    void resolveRecreatesPoolWhenCachedPoolIsClosed() {
        var firstPool = mock(HikariDataSource.class);
        when(firstPool.isClosed()).thenReturn(true);
        var secondPool = mock(HikariDataSource.class);
        when(datasourceLookupService.findById(id)).thenReturn(Optional.of(activeDescriptor));
        when(poolFactory.createPool(activeDescriptor)).thenReturn(firstPool, secondPool);

        var first = manager.resolve(id);
        var second = manager.resolve(id);

        assertThat(first).isSameAs(firstPool);
        assertThat(second).isSameAs(secondPool);
        verify(poolFactory, times(2)).createPool(any());
    }

    @Test
    void resolveThrowsWhenDatasourceMissing() {
        when(datasourceLookupService.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.resolve(id))
                .isInstanceOf(DatasourceUnavailableException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void resolveThrowsWhenDatasourceInactive() {
        var inactive = new DatasourceConnectionDescriptor(
                id, DbType.POSTGRESQL, "h", 5432, "db", "u", "ENC", SslMode.DISABLE, 10, 1000, false);
        when(datasourceLookupService.findById(id)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> manager.resolve(id))
                .isInstanceOf(DatasourceUnavailableException.class)
                .hasMessageContaining("not active");
        verify(poolFactory, never()).createPool(any());
    }

    @Test
    void resolveWrapsFactoryFailureInPoolInitializationException() {
        var hikariFailure = new RuntimeException("connection refused");
        when(datasourceLookupService.findById(id)).thenReturn(Optional.of(activeDescriptor));
        when(poolFactory.createPool(activeDescriptor)).thenThrow(hikariFailure);

        assertThatThrownBy(() -> manager.resolve(id))
                .isInstanceOf(PoolInitializationException.class)
                .hasCause(hikariFailure);
    }

    @Test
    void evictClosesAndRemovesPool() {
        var pool = mock(HikariDataSource.class);
        when(datasourceLookupService.findById(id)).thenReturn(Optional.of(activeDescriptor));
        when(poolFactory.createPool(activeDescriptor)).thenReturn(pool);
        manager.resolve(id);

        manager.evict(id);

        verify(pool).close();
        // Next resolve recreates
        var newPool = mock(HikariDataSource.class);
        when(poolFactory.createPool(activeDescriptor)).thenReturn(newPool);
        assertThat(manager.resolve(id)).isSameAs(newPool);
    }

    @Test
    void evictNoopWhenNoPoolCached() {
        manager.evict(id);

        verify(poolFactory, never()).createPool(any());
    }

    @Test
    void shutdownClosesAllPools() {
        var idA = UUID.randomUUID();
        var idB = UUID.randomUUID();
        var poolA = mock(HikariDataSource.class);
        var poolB = mock(HikariDataSource.class);
        var descA = new DatasourceConnectionDescriptor(idA, DbType.POSTGRESQL, "a", 5432, "d",
                "u", "ENC", SslMode.DISABLE, 10, 1000, true);
        var descB = new DatasourceConnectionDescriptor(idB, DbType.MYSQL, "b", 3306, "d",
                "u", "ENC", SslMode.DISABLE, 10, 1000, true);
        when(datasourceLookupService.findById(idA)).thenReturn(Optional.of(descA));
        when(datasourceLookupService.findById(idB)).thenReturn(Optional.of(descB));
        when(poolFactory.createPool(descA)).thenReturn(poolA);
        when(poolFactory.createPool(descB)).thenReturn(poolB);
        manager.resolve(idA);
        manager.resolve(idB);

        manager.shutdown();

        verify(poolA).close();
        verify(poolB).close();
    }
}
