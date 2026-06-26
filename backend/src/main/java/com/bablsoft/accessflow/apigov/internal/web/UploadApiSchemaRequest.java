package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UploadApiSchemaRequest(
        @NotNull(message = "{validation.api_schema.type.required}")
        ApiSchemaType schemaType,
        @NotBlank(message = "{validation.api_schema.content.required}")
        String rawContent,
        String sourceUrl) {
}
