package com.bablsoft.accessflow.lifecycle.api;

import java.util.List;
import java.util.UUID;

/**
 * Command to create a retention policy. Validation of business invariants (target presence, transform
 * required for PSEUDONYMIZE, parseable retention window) lives in the service implementation;
 * field-shape validation lives on the web request DTO.
 */
public record CreateRetentionPolicyCommand(
        UUID organizationId,
        UUID datasourceId,
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
        ErasureConditionSet conditions,
        String rawWhere,
        String cronSchedule,
        boolean enabled,
        UUID createdBy) {

    public CreateRetentionPolicyCommand {
        targetColumns = targetColumns == null ? List.of() : List.copyOf(targetColumns);
    }
}
