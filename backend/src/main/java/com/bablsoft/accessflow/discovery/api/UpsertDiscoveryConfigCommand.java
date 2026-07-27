package com.bablsoft.accessflow.discovery.api;

/**
 * Upsert command for a datasource's discovery settings (AF-623). {@code null} fields keep the
 * current (or default) value, mirroring the partial-update convention of
 * {@code UpdateDatasourceCommand}.
 */
public record UpsertDiscoveryConfigCommand(
        Boolean enabled,
        Integer sampleSize,
        Integer scanIntervalHours,
        Boolean aiClassificationEnabled) {
}
