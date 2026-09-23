package com.bablsoft.accessflow.schemachange.api;

import java.util.UUID;

/**
 * Creates or replaces one pipeline's drift configuration (#881). Every field is total: the write is
 * a replacement, not a patch, so an omitted baseline environment clears the designation rather than
 * quietly keeping a stale one.
 */
public record UpsertSchemaDriftConfigCommand(boolean enabled, SchemaDriftBaseline baseline,
                                             UUID baselineEnvironmentId, int scanIntervalHours) {
}
