package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

public record DataClassificationTagView(
        UUID id,
        UUID datasourceId,
        String tableName,
        String columnName,
        DataClassification classification,
        String note,
        Instant createdAt,
        Instant updatedAt) {
}
