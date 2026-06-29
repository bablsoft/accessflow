package com.bablsoft.accessflow.lifecycle.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read view of a retention policy, enriched with the datasource name for display. Used for the admin
 * list/detail and as the cross-module lookup payload (so other modules never touch
 * {@code lifecycle.internal}).
 */
public record RetentionPolicyView(
        UUID id,
        UUID organizationId,
        UUID datasourceId,
        String datasourceName,
        String name,
        String description,
        String targetTable,
        List<String> targetColumns,
        String classificationTag,
        String timestampColumn,
        String retentionWindow,
        LifecycleAction action,
        LifecycleTransform transformType,
        String softDeleteColumn,
        boolean enabled,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
