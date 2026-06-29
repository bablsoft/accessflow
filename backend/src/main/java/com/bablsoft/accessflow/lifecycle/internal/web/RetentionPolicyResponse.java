package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecycleTransform;
import com.bablsoft.accessflow.lifecycle.api.RetentionPolicyView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API response for a retention policy. */
public record RetentionPolicyResponse(
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

    static RetentionPolicyResponse from(RetentionPolicyView v) {
        return new RetentionPolicyResponse(v.id(), v.organizationId(), v.datasourceId(),
                v.datasourceName(), v.name(), v.description(), v.targetTable(), v.targetColumns(),
                v.classificationTag(), v.timestampColumn(), v.retentionWindow(), v.action(),
                v.transformType(), v.softDeleteColumn(), v.enabled(), v.createdBy(),
                v.createdAt(), v.updatedAt());
    }
}
