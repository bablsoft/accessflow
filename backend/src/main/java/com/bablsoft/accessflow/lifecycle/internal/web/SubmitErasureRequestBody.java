package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.lifecycle.api.ErasureConditionSet;
import com.bablsoft.accessflow.lifecycle.api.LifecycleSubjectType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Self-service request to erase a data subject's records in a datasource. Since AF-519 the request
 * may be driven by the legacy subject shape ({@code subjectType}+{@code subjectIdentifier}) and/or a
 * richer configuration ({@code targetTable} + {@code targetColumns} + structured {@code conditions}
 * and/or a raw {@code rawWhere}). The service enforces that at least one shape is present, and that a
 * target table accompanies conditions / raw WHERE (mapped to HTTP 422).
 */
public record SubmitErasureRequestBody(
        @NotNull(message = "{validation.lifecycle.datasource.required}")
        UUID datasourceId,

        LifecycleSubjectType subjectType,

        @Size(max = 255, message = "{validation.lifecycle.subject_identifier.size}")
        String subjectIdentifier,

        @Size(max = 255, message = "{validation.lifecycle.target_table.size}")
        String targetTable,

        List<@Size(max = 255, message = "{validation.lifecycle.target_column.size}") String> targetColumns,

        ErasureConditionSet conditions,

        @Size(max = 4000, message = "{validation.lifecycle.raw_where.size}")
        String rawWhere,

        @Size(max = 2000, message = "{validation.lifecycle.reason.size}")
        String reason) {
}
