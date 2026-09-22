package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.UpdateSchemaChangeSetCommand;
import jakarta.validation.constraints.Size;

/** Every field is null-means-unchanged; {@code status} may only be moved to {@code ARCHIVED}. */
public record UpdateSchemaChangeSetRequest(
        @Size(min = 3, max = 255, message = "{validation.schema_change_set.name.size}")
        String name,
        @Size(max = 2000, message = "{validation.schema_change_set.description.size}")
        String description,
        SchemaChangeSetStatus status) {

    UpdateSchemaChangeSetCommand toCommand() {
        return new UpdateSchemaChangeSetCommand(name, description, status);
    }
}
