package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Org-wide classification tag projection for compliance reporting consumers (#459). Carries the
 * datasource name alongside the tag so a report can list "which objects are sensitive" without a
 * second lookup.
 */
public record OrganizationDataClassificationView(
        UUID id,
        UUID datasourceId,
        String datasourceName,
        String tableName,
        String columnName,
        DataClassification classification,
        String note,
        Instant createdAt,
        Instant updatedAt) {
}
