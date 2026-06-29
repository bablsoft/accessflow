package com.bablsoft.accessflow.lifecycle.api;

import java.util.List;
import java.util.UUID;

/** Command to update an existing retention policy. */
public record UpdateRetentionPolicyCommand(
        UUID policyId,
        UUID organizationId,
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
        boolean enabled) {

    public UpdateRetentionPolicyCommand {
        targetColumns = targetColumns == null ? List.of() : List.copyOf(targetColumns);
    }
}
