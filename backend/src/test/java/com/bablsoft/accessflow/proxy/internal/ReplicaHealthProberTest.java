package com.bablsoft.accessflow.proxy.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.TaskScheduler;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplicaHealthProberTest {

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID endpointId = UUID.randomUUID();

    @Mock
    private DefaultDatasourceConnectionPoolManager poolManager;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private DataSource pool;
    @Mock
    private Connection connection;

    private ReplicaHealthRegistry registry;
    private ReplicaHealthProber prober;

    @BeforeEach
    void setUp() throws SQLException {
        registry = new ReplicaHealthRegistry(java.time.Clock.systemUTC(),
                new ProxyReplicaProperties(null, Duration.ofSeconds(2), null));
        prober = new ReplicaHealthProber(poolManager, registry,
                new ProxyReplicaProperties(null, Duration.ofSeconds(2), null), taskScheduler);
        when(pool.getConnection()).thenReturn(connection);
        doAnswer(invocation -> {
            var visitor = invocation
                    .getArgument(0, DefaultDatasourceConnectionPoolManager.ReplicaPoolVisitor.class);
            visitor.visit(datasourceId, endpointId, pool);
            return null;
        }).when(poolManager).forEachCachedReplicaPool(any());
    }

    @Test
    void validConnectionRecordsSuccess() throws SQLException {
        registry.recordFailure(datasourceId, endpointId);
        when(connection.isValid(anyInt())).thenReturn(true);

        prober.probeAll();

        assertThat(registry.isHealthy(datasourceId, endpointId)).isTrue();
        verify(connection).isValid(2);
        verify(connection).close();
    }

    @Test
    void invalidConnectionRecordsFailure() throws SQLException {
        when(connection.isValid(anyInt())).thenReturn(false);

        prober.probeAll();

        assertThat(registry.isHealthy(datasourceId, endpointId)).isFalse();
    }

    @Test
    void connectionFailureRecordsFailure() throws SQLException {
        when(pool.getConnection()).thenThrow(new SQLException("gone"));

        prober.probeAll();

        assertThat(registry.isHealthy(datasourceId, endpointId)).isFalse();
    }

    @Test
    void startSchedulesAndStopCancels() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(future).when(taskScheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));

        prober.start();
        prober.stop();

        verify(taskScheduler).scheduleWithFixedDelay(any(Runnable.class),
                org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(30)));
        verify(future).cancel(false);
    }
}
