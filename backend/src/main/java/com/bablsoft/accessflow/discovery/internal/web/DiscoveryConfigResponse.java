package com.bablsoft.accessflow.discovery.internal.web;

import com.bablsoft.accessflow.discovery.api.DiscoveryScanConfigView;

import java.time.Instant;
import java.util.UUID;

public record DiscoveryConfigResponse(
        UUID datasourceId,
        boolean enabled,
        int sampleSize,
        int scanIntervalHours,
        boolean aiClassificationEnabled,
        Instant lastScanAt,
        String lastScanError) {

    public static DiscoveryConfigResponse from(DiscoveryScanConfigView view) {
        return new DiscoveryConfigResponse(view.datasourceId(), view.enabled(), view.sampleSize(),
                view.scanIntervalHours(), view.aiClassificationEnabled(), view.lastScanAt(),
                view.lastScanError());
    }
}
