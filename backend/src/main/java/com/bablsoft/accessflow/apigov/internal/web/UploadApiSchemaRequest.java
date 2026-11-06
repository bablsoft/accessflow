package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.OperationFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UploadApiSchemaRequest(
        @NotNull(message = "{validation.api_schema.type.required}")
        ApiSchemaType schemaType,
        String rawContent,
        String sourceUrl,
        @Valid OperationFilterRequest filter,
        @Min(value = 1, message = "{validation.api_schema.refresh_interval.min}")
        Integer refreshIntervalMinutes) {

    /** Either the document itself or a URL to fetch it from — the SPA sends an empty body for a URL import. */
    @AssertTrue(message = "{validation.api_schema.content.required}")
    public boolean isContentOrSourceUrlPresent() {
        return (rawContent != null && !rawContent.isBlank()) || (sourceUrl != null && !sourceUrl.isBlank());
    }

    OperationFilter toFilter() {
        return filter == null ? OperationFilter.EMPTY : filter.toDomain();
    }
}
