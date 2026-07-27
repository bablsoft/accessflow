package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.discovery.api.DiscoveryScanAlreadyRunningException;
import com.bablsoft.accessflow.discovery.api.DiscoveryScanTriggerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultDiscoveryScanTriggerService implements DiscoveryScanTriggerService {

    private final DiscoveryScanService scanService;
    private final DatasourceAdminService datasourceAdminService;
    private final ExecutorService discoveryScanExecutor;

    @Override
    public void requestScan(UUID datasourceId, UUID organizationId, UUID actorId) {
        // 404 (DatasourceNotFoundException) when the datasource is not in the caller's org.
        datasourceAdminService.getForAdmin(datasourceId, organizationId);
        if (scanService.isInFlight(datasourceId)) {
            throw new DiscoveryScanAlreadyRunningException(datasourceId);
        }
        discoveryScanExecutor.submit(() -> {
            try {
                scanService.scan(datasourceId, organizationId, actorId);
            } catch (DiscoveryScanAlreadyRunningException ex) {
                // Raced another trigger between the pre-check and the scan; the winner proceeds.
                log.info("Discovery scan for datasource {} already running; trigger skipped",
                        datasourceId);
            } catch (RuntimeException ex) {
                log.error("On-demand discovery scan failed for datasource {}", datasourceId, ex);
            }
        });
    }
}
