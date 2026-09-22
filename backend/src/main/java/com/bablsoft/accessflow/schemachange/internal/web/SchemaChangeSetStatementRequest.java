package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One statement as posted; the list position is the statement order. */
public record SchemaChangeSetStatementRequest(
        @NotBlank(message = "{validation.schema_change_set.statement.sql_text.required}")
        @Size(max = 100_000, message = "{validation.schema_change_set.statement.sql_text.max}")
        String sqlText) {

    SchemaChangeSetStatementInput toInput() {
        return new SchemaChangeSetStatementInput(sqlText);
    }
}
