package com.bablsoft.accessflow.compliance.api;

import com.bablsoft.accessflow.core.api.DataClassification;

/**
 * A single classification tag that a query's referenced table matched. {@code columnName} is null
 * for a table-level tag.
 */
public record MatchedClassification(String tableName, String columnName,
                                    DataClassification classification) {
}
