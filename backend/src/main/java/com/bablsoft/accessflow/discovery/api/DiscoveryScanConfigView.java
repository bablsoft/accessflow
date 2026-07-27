package com.bablsoft.accessflow.discovery.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-datasource discovery settings (AF-623). When a datasource has no persisted config row the
 * service synthesizes this view with the defaults ({@code enabled=false}, {@code sampleSize=100},
 * {@code scanIntervalHours=24}, {@code aiClassificationEnabled=false}).
 */
public record DiscoveryScanConfigView(
        UUID datasourceId,
        boolean enabled,
        int sampleSize,
        int scanIntervalHours,
        boolean aiClassificationEnabled,
        Instant lastScanAt,
        String lastScanError) {
}
