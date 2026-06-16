package com.bablsoft.accessflow.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of a saved query template at a point in time (AF-442). Returned by
 * {@link QueryTemplateVersionService}; carries the full template body so two versions can be diffed
 * client-side without further round-trips.
 */
public record QueryTemplateVersionView(
        UUID id,
        UUID templateId,
        int versionNumber,
        UUID datasourceId,
        String name,
        String body,
        String description,
        List<String> tags,
        QueryTemplateVisibility visibility,
        QueryTemplateChangeType changeType,
        UUID authorId,
        String authorDisplayName,
        Instant createdAt) {
}
