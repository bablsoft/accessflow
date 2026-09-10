package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.discovery.api.DiscoveryScanAlreadyRunningException;
import com.bablsoft.accessflow.discovery.api.DiscoveryScanTriggerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultDiscoveryScanTriggerService implements DiscoveryScanTriggerService {

    private final DiscoveryScanService scanService;
    private final DatasourceAdminService datasourceAdminService;

    @Override
    public void requestScan(UUID datasourceId, UUID organizationId, UUID actorId) {
        // 404 (DatasourceNotFoundException) when the datasource is not in the caller's org.
        datasourceAdminService.getForAdmin(datasourceId, organizationId);
        // The cluster lock is taken here, not inside the submitted task, so a "no" is a real 409
        // rather than a 202 the caller would never hear back about.
        if (!scanService.scanAsync(datasourceId, organizationId, actorId)) {
            throw new DiscoveryScanAlreadyRunningException(datasourceId);
        }
    }
}
