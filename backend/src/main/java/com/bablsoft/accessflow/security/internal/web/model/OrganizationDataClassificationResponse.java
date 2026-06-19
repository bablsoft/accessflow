package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.OrganizationDataClassificationView;

import java.time.Instant;
import java.util.UUID;

public record OrganizationDataClassificationResponse(
        UUID id,
        UUID datasourceId,
        String datasourceName,
        String tableName,
        String columnName,
        DataClassification classification,
        String note,
        Instant createdAt,
        Instant updatedAt) {

    public static OrganizationDataClassificationResponse from(OrganizationDataClassificationView view) {
        return new OrganizationDataClassificationResponse(
                view.id(),
                view.datasourceId(),
                view.datasourceName(),
                view.tableName(),
                view.columnName(),
                view.classification(),
                view.note(),
                view.createdAt(),
                view.updatedAt());
    }
}
