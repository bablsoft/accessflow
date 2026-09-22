package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.CreateSchemaChangeSetCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateSchemaChangeSetRequest(
        @NotNull(message = "{validation.schema_change_set.pipeline_id.required}")
        UUID pipelineId,
        @NotBlank(message = "{validation.schema_change_set.name.required}")
        @Size(min = 3, max = 255, message = "{validation.schema_change_set.name.size}")
        String name,
        @Size(max = 2000, message = "{validation.schema_change_set.description.size}")
        String description,
        @Valid
        List<SchemaChangeSetStatementRequest> statements) {

    CreateSchemaChangeSetCommand toCommand() {
        return new CreateSchemaChangeSetCommand(pipelineId, name, description,
                statements == null ? List.of() : statements.stream().map(SchemaChangeSetStatementRequest::toInput).toList());
    }
}
