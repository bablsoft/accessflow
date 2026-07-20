package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ReorderApiConnectorVariablesCommand;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/** The connector's complete variable id list, in the desired evaluation order. */
public record ReorderApiConnectorVariablesRequest(
        @NotEmpty(message = "{validation.api_connector_variable.reorder.required}")
        List<UUID> variableIds) {

    public ReorderApiConnectorVariablesCommand toCommand() {
        return new ReorderApiConnectorVariablesCommand(variableIds);
    }
}
