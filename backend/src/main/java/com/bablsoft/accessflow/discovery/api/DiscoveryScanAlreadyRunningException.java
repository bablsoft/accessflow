package com.bablsoft.accessflow.discovery.api;

import java.util.UUID;

/**
 * A discovery scan for the datasource is already running somewhere in the cluster (AF-623;
 * cluster-wide since AF-660) — HTTP 409.
 */
public class DiscoveryScanAlreadyRunningException extends DiscoveryException {

    private final UUID datasourceId;

    public DiscoveryScanAlreadyRunningException(UUID datasourceId) {
        super("Discovery scan already running for datasource " + datasourceId);
        this.datasourceId = datasourceId;
    }

    public UUID datasourceId() {
        return datasourceId;
    }
}
