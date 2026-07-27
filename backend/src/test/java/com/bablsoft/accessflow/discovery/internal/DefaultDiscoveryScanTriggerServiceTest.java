package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.discovery.api.DiscoveryScanAlreadyRunningException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDiscoveryScanTriggerServiceTest {

    @Mock
    private DiscoveryScanService scanService;
    @Mock
    private DatasourceAdminService datasourceAdminService;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final UUID dsId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @AfterEach
    void shutDown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    private DefaultDiscoveryScanTriggerService service() {
        return new DefaultDiscoveryScanTriggerService(scanService, datasourceAdminService,
                executor);
    }

    @Test
    void submitsScanAsynchronously() {
        service().requestScan(dsId, orgId, actorId);

        verify(scanService, timeout(5000)).scan(dsId, orgId, actorId);
    }

    @Test
    void unknownDatasourceRejectsSynchronously() {
        when(datasourceAdminService.getForAdmin(dsId, orgId))
                .thenThrow(new DatasourceNotFoundException(dsId));

        assertThatThrownBy(() -> service().requestScan(dsId, orgId, actorId))
                .isInstanceOf(DatasourceNotFoundException.class);
        verify(scanService, never()).scan(any(), any(), any());
    }

    @Test
    void inFlightScanRejectsWith409Synchronously() {
        when(scanService.isInFlight(dsId)).thenReturn(true);

        assertThatThrownBy(() -> service().requestScan(dsId, orgId, actorId))
                .isInstanceOf(DiscoveryScanAlreadyRunningException.class);
        verify(scanService, never()).scan(any(), any(), any());
    }

    @Test
    void raceLostInsideTaskIsSwallowed() {
        doThrow(new DiscoveryScanAlreadyRunningException(dsId))
                .when(scanService).scan(dsId, orgId, actorId);

        service().requestScan(dsId, orgId, actorId);

        verify(scanService, timeout(5000)).scan(dsId, orgId, actorId);
    }
}
