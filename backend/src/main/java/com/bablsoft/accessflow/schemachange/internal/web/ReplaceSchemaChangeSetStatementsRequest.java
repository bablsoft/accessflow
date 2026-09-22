package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** The whole ordered statement list; an empty list clears the set. */
public record ReplaceSchemaChangeSetStatementsRequest(
        @NotNull(message = "{validation.schema_change_set.statements.required}")
        @Valid
        List<SchemaChangeSetStatementRequest> statements) {

    List<SchemaChangeSetStatementInput> toInputs() {
        return statements.stream().map(SchemaChangeSetStatementRequest::toInput).toList();
    }
}
