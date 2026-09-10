package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.discovery.api.DiscoveryScanAlreadyRunningException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDiscoveryScanTriggerServiceTest {

    @Mock
    private DiscoveryScanService scanService;
    @Mock
    private DatasourceAdminService datasourceAdminService;

    private final UUID dsId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    private DefaultDiscoveryScanTriggerService service() {
        return new DefaultDiscoveryScanTriggerService(scanService, datasourceAdminService);
    }

    @Test
    void handsTheScanToTheAsyncPath() {
        when(scanService.scanAsync(dsId, orgId, actorId)).thenReturn(true);

        service().requestScan(dsId, orgId, actorId);

        verify(scanService).scanAsync(dsId, orgId, actorId);
    }

    @Test
    void unknownDatasourceRejectsBeforeTakingTheLock() {
        when(datasourceAdminService.getForAdmin(dsId, orgId))
                .thenThrow(new DatasourceNotFoundException(dsId));

        assertThatThrownBy(() -> service().requestScan(dsId, orgId, actorId))
                .isInstanceOf(DatasourceNotFoundException.class);
        verify(scanService, never()).scanAsync(any(), any(), any());
    }

    @Test
    void scanRunningOnAnyReplicaRejectsWith409() {
        when(scanService.scanAsync(dsId, orgId, actorId)).thenReturn(false);

        assertThatThrownBy(() -> service().requestScan(dsId, orgId, actorId))
                .isInstanceOf(DiscoveryScanAlreadyRunningException.class);
    }

    @Test
    void lockProviderFailurePropagatesRatherThanScanningUnguarded() {
        // Redis backs the lock, so an outage is an app-level outage. Failing the request is the
        // fail-closed direction: a scan that cannot take the lock could double up on the customer
        // database, which is the whole point of AF-660.
        var redisDown = new IllegalStateException("redis unreachable");
        when(scanService.scanAsync(dsId, orgId, actorId)).thenThrow(redisDown);

        assertThatThrownBy(() -> service().requestScan(dsId, orgId, actorId))
                .isSameAs(redisDown);
    }
}
