package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationTagView;

import java.time.Instant;
import java.util.UUID;

public record DataClassificationTagResponse(
        UUID id,
        UUID datasourceId,
        String tableName,
        String columnName,
        DataClassification classification,
        String note,
        Instant createdAt,
        Instant updatedAt) {

    public static DataClassificationTagResponse from(DataClassificationTagView view) {
        return new DataClassificationTagResponse(
                view.id(),
                view.datasourceId(),
                view.tableName(),
                view.columnName(),
                view.classification(),
                view.note(),
                view.createdAt(),
                view.updatedAt());
    }
}
