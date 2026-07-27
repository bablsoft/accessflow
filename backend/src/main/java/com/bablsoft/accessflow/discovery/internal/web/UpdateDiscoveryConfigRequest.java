package com.bablsoft.accessflow.discovery.internal.web;

import com.bablsoft.accessflow.discovery.api.UpsertDiscoveryConfigCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Partial update — {@code null} fields keep their current value. */
public record UpdateDiscoveryConfigRequest(
        Boolean enabled,
        @Min(value = 10, message = "{validation.discovery.sample_size.range}")
        @Max(value = 1000, message = "{validation.discovery.sample_size.range}")
        Integer sampleSize,
        @Min(value = 1, message = "{validation.discovery.scan_interval.range}")
        @Max(value = 720, message = "{validation.discovery.scan_interval.range}")
        Integer scanIntervalHours,
        Boolean aiClassificationEnabled) {

    public UpsertDiscoveryConfigCommand toCommand() {
        return new UpsertDiscoveryConfigCommand(enabled, sampleSize, scanIntervalHours,
                aiClassificationEnabled);
    }
}
