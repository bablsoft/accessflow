package com.bablsoft.accessflow.discovery.api;

import java.util.UUID;

/**
 * On-demand "Scan now" trigger (AF-623). The scan runs asynchronously on a virtual thread;
 * ad-hoc scans are allowed even when the datasource has not opted into scheduled discovery.
 */
public interface DiscoveryScanTriggerService {

    /**
     * @throws DiscoveryScanAlreadyRunningException when a scan for the datasource is already
     *         in flight on this node (HTTP 409)
     */
    void requestScan(UUID datasourceId, UUID organizationId, UUID actorId);
}
