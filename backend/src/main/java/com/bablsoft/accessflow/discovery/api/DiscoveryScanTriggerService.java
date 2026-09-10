package com.bablsoft.accessflow.discovery.api;

import java.util.UUID;

/**
 * On-demand "Scan now" trigger (AF-623). The scan runs asynchronously on a virtual thread;
 * ad-hoc scans are allowed even when the datasource has not opted into scheduled discovery.
 */
public interface DiscoveryScanTriggerService {

    /**
     * Starts a scan, unless one is already running for this datasource.
     *
     * <p>The guard is a cluster lock, so it can itself be unavailable. When it is, this fails
     * rather than starting an unguarded scan — the fail-closed direction, since an unguarded scan
     * is the double-sampling the guard exists to prevent.
     *
     * @throws DiscoveryScanAlreadyRunningException when a scan for the datasource is already
     *         running somewhere in the cluster (HTTP 409)
     */
    void requestScan(UUID datasourceId, UUID organizationId, UUID actorId);
}
