package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.lifecycle.api.ErasureConditionSet;
import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecycleTransform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Admin request to create a retention policy. */
public record CreateRetentionPolicyRequest(
        @NotNull(message = "{validation.lifecycle.datasource.required}")
        UUID datasourceId,

        @NotBlank(message = "{validation.lifecycle.name.required}")
        @Size(min = 3, max = 100, message = "{validation.lifecycle.name.size}")
        String name,

        @Size(max = 2000, message = "{validation.lifecycle.description.size}")
        String description,

        @Size(max = 255, message = "{validation.lifecycle.target_table.size}")
        String targetTable,

        List<@Size(max = 255, message = "{validation.lifecycle.target_column.size}") String> targetColumns,

        @Size(max = 100, message = "{validation.lifecycle.classification_tag.size}")
        String classificationTag,

        @NotBlank(message = "{validation.lifecycle.timestamp_column.required}")
        @Size(max = 255, message = "{validation.lifecycle.timestamp_column.size}")
        String timestampColumn,

        @NotBlank(message = "{validation.lifecycle.retention_window.required}")
        @Size(max = 50, message = "{validation.lifecycle.retention_window.size}")
        String retentionWindow,

        @NotNull(message = "{validation.lifecycle.action.required}")
        LifecycleAction action,

        LifecycleTransform transformType,

        @Size(max = 255, message = "{validation.lifecycle.soft_delete_column.size}")
        String softDeleteColumn,

        ErasureConditionSet conditions,

        @Size(max = 4000, message = "{validation.lifecycle.raw_where.size}")
        String rawWhere,

        @Size(max = 100, message = "{validation.lifecycle.cron_schedule.size}")
        String cronSchedule,

        Boolean enabled) {
}
