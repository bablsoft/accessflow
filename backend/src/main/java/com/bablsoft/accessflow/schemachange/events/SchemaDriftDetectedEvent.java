package com.bablsoft.accessflow.schemachange.events;

import java.util.UUID;

/**
 * Published once per environment scan that <em>opened</em> at least one drift finding (#882) — a
 * newly created finding, a {@code RESOLVED} one that came back, or an {@code ACKNOWLEDGED} one whose
 * values changed. A finding merely re-seen while already open never counts, so a persistent
 * divergence does not alert on every scan. Published from
 * the scan path, which holds no transaction.
 */
public record SchemaDriftDetectedEvent(
        UUID scanId,
        UUID organizationId,
        UUID pipelineId,
        UUID environmentId,
        int newFindingCount) {
}
