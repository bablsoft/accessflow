package com.bablsoft.accessflow.core.api;

import java.util.List;

/**
 * Command to tag a datasource object with one or more data classifications. One command yields one
 * persisted tag per entry in {@code classifications}.
 *
 * @param tableName       the table being tagged (required)
 * @param columnName      the column being tagged; {@code null}/blank tags the table itself
 * @param classifications the classifications to apply (at least one)
 * @param note            optional free-text note
 * @param applyMasking    when {@code null} or {@code true}, a column-level tag whose classification
 *                        has a masking default auto-creates a masking policy (idempotent). Ignored
 *                        for table-level tags.
 */
public record CreateDataClassificationTagCommand(
        String tableName,
        String columnName,
        List<DataClassification> classifications,
        String note,
        Boolean applyMasking) {

    public CreateDataClassificationTagCommand {
        classifications = classifications == null ? List.of() : List.copyOf(classifications);
    }
}
