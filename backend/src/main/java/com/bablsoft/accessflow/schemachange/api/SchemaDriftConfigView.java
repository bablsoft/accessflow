package com.bablsoft.accessflow.schemachange.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One pipeline's drift scan configuration (#881). {@code id} is null for the synthesized view of a
 * pipeline that has never been configured — absence means the same thing as {@code enabled = false},
 * and the read surface says so rather than 404-ing.
 *
 * <p>{@code lastScanError} carries the stable reason code of the most recent scan, not localized
 * prose: the row is written by a background job and read in every locale.
 */
public record SchemaDriftConfigView(
        UUID id,
        UUID organizationId,
        UUID pipelineId,
        boolean enabled,
        SchemaDriftBaseline baseline,
        UUID baselineEnvironmentId,
        int scanIntervalHours,
        Instant lastScanAt,
        String lastScanError
) {
}
