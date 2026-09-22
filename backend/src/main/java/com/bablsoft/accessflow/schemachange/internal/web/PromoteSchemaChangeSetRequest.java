package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.PromoteSchemaChangeSetCommand;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PromoteSchemaChangeSetRequest(
        @NotNull(message = "{validation.schema_change_promotion.environment_id.required}")
        UUID environmentId) {

    PromoteSchemaChangeSetCommand toCommand(String submittedIp, String submittedUserAgent) {
        return new PromoteSchemaChangeSetCommand(environmentId, submittedIp, submittedUserAgent);
    }
}
