package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.OperationFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UploadApiSchemaRequest(
        @NotNull(message = "{validation.api_schema.type.required}")
        ApiSchemaType schemaType,
        @NotBlank(message = "{validation.api_schema.content.required}")
        String rawContent,
        String sourceUrl,
        @Valid OperationFilterRequest filter) {

    OperationFilter toFilter() {
        return filter == null ? OperationFilter.EMPTY : filter.toDomain();
    }
}
