package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.lifecycle.api.RetentionPolicyView;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Enriches a {@link RetentionPolicyEntity} with the datasource name for display. */
@Component
@RequiredArgsConstructor
class RetentionPolicyViewMapper {

    private final DatasourceLookupService datasourceLookupService;
    private final ErasureConditionCodec conditionCodec;

    RetentionPolicyView toView(RetentionPolicyEntity entity) {
        var datasourceName = datasourceLookupService.findRef(entity.getDatasourceId())
                .map(DatasourceRef::name).orElse(null);
        return new RetentionPolicyView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getDatasourceId(),
                datasourceName,
                entity.getName(),
                entity.getDescription(),
                entity.getTargetTable(),
                toList(entity.getTargetColumns()),
                entity.getClassificationTag(),
                entity.getTimestampColumn(),
                entity.getRetentionWindow(),
                entity.getAction(),
                entity.getTransformType(),
                entity.getSoftDeleteColumn(),
                conditionCodec.fromJson(entity.getConditions()),
                entity.getRawWhere(),
                entity.getCronSchedule(),
                entity.getLastRunAt(),
                entity.getNextRunAt(),
                entity.isEnabled(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    static List<String> toList(String[] values) {
        return values == null ? List.of() : List.of(values);
    }
}
