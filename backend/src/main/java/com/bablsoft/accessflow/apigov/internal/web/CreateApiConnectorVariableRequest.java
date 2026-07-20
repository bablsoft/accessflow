package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiVariableAlgorithm;
import com.bablsoft.accessflow.apigov.api.ApiVariableEncoding;
import com.bablsoft.accessflow.apigov.api.ApiVariableKind;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorVariableCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateApiConnectorVariableRequest(
        @NotBlank(message = "{validation.api_connector_variable.name.required}")
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,63}$",
                message = "{validation.api_connector_variable.name.invalid}")
        String name,
        @NotNull(message = "{validation.api_connector_variable.kind.required}")
        ApiVariableKind kind,
        @Size(max = 8192, message = "{validation.api_connector_variable.expression.too_long}")
        String expression,
        ApiVariableAlgorithm algorithm,
        ApiVariableEncoding encoding,
        @Size(max = 4096, message = "{validation.api_connector_variable.secret.too_long}")
        String secret,
        @Size(max = 140, message = "{validation.api_connector_variable.target.invalid}")
        String target,
        Boolean overridable,
        @Size(max = 512, message = "{validation.api_connector_variable.description.too_long}")
        String description,
        Integer sortOrder) {

    public CreateApiConnectorVariableCommand toCommand() {
        return new CreateApiConnectorVariableCommand(name, kind, expression, algorithm, encoding,
                secret, target, overridable, description, sortOrder);
    }
}
