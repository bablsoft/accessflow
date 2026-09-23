package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import com.bablsoft.accessflow.schemachange.api.UpsertSchemaDriftConfigCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * A total replacement of one pipeline's drift configuration. The primitives are boxed and
 * {@code @NotNull} on purpose: an absent primitive fails Jackson 3's FAIL_ON_NULL_FOR_PRIMITIVES
 * before validation runs, which surfaces as a 500 rather than the 400 a missing field deserves.
 */
public record UpsertSchemaDriftConfigRequest(
        @NotNull(message = "{validation.schema_drift_config.enabled.required}")
        Boolean enabled,

        @NotNull(message = "{validation.schema_drift_config.baseline.required}")
        SchemaDriftBaseline baseline,

        UUID baselineEnvironmentId,

        @NotNull(message = "{validation.schema_drift_config.scan_interval_hours.required}")
        @Min(value = 1, message = "{validation.schema_drift_config.scan_interval_hours.range}")
        @Max(value = 720, message = "{validation.schema_drift_config.scan_interval_hours.range}")
        Integer scanIntervalHours) {

    UpsertSchemaDriftConfigCommand toCommand() {
        return new UpsertSchemaDriftConfigCommand(enabled, baseline, baselineEnvironmentId,
                scanIntervalHours);
    }
}
