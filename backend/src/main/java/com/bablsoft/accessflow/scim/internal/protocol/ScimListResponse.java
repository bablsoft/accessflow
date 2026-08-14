package com.bablsoft.accessflow.scim.internal.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ScimListResponse<T>(
        List<String> schemas,
        long totalResults,
        int startIndex,
        int itemsPerPage,
        @JsonProperty("Resources") List<T> resources
) {
    public static <T> ScimListResponse<T> of(long totalResults, int startIndex, List<T> resources) {
        return new ScimListResponse<>(List.of(ScimSchemas.LIST_RESPONSE), totalResults, startIndex,
                resources.size(), resources);
    }
}
