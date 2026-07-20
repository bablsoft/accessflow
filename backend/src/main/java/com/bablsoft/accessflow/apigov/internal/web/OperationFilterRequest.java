package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.OperationFilter;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Web request mirror of {@link OperationFilter} with bounded-size Bean Validation. Path and
 * operation-id entries are globs; verb and tag entries are exact case-insensitive matches.
 * {@code excludeDeprecated} is boxed so an omitted field deserializes to {@code null} (coerced to
 * {@code false}) rather than 500-ing on a primitive.
 */
public record OperationFilterRequest(
        @Size(max = 100, message = "{validation.api_schema_filter.list.size}")
        List<@Size(max = 200, message = "{validation.api_schema_filter.pattern.size}") String> includePaths,
        @Size(max = 100, message = "{validation.api_schema_filter.list.size}")
        List<@Size(max = 200, message = "{validation.api_schema_filter.pattern.size}") String> excludePaths,
        @Size(max = 100, message = "{validation.api_schema_filter.list.size}")
        List<@Size(max = 200, message = "{validation.api_schema_filter.pattern.size}") String> includeVerbs,
        @Size(max = 100, message = "{validation.api_schema_filter.list.size}")
        List<@Size(max = 200, message = "{validation.api_schema_filter.pattern.size}") String> excludeVerbs,
        @Size(max = 100, message = "{validation.api_schema_filter.list.size}")
        List<@Size(max = 200, message = "{validation.api_schema_filter.pattern.size}") String> includeOperationIds,
        @Size(max = 100, message = "{validation.api_schema_filter.list.size}")
        List<@Size(max = 200, message = "{validation.api_schema_filter.pattern.size}") String> excludeOperationIds,
        @Size(max = 100, message = "{validation.api_schema_filter.list.size}")
        List<@Size(max = 200, message = "{validation.api_schema_filter.pattern.size}") String> includeTags,
        @Size(max = 100, message = "{validation.api_schema_filter.list.size}")
        List<@Size(max = 200, message = "{validation.api_schema_filter.pattern.size}") String> excludeTags,
        Boolean excludeDeprecated) {

    OperationFilter toDomain() {
        return new OperationFilter(includePaths, excludePaths, includeVerbs, excludeVerbs,
                includeOperationIds, excludeOperationIds, includeTags, excludeTags,
                Boolean.TRUE.equals(excludeDeprecated));
    }
}
