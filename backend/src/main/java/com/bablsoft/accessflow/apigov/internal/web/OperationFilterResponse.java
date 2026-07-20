package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.OperationFilter;

import java.util.List;

/** Web response mirror of {@link OperationFilter} so the admin UI can display and re-edit it. */
public record OperationFilterResponse(
        List<String> includePaths,
        List<String> excludePaths,
        List<String> includeVerbs,
        List<String> excludeVerbs,
        List<String> includeOperationIds,
        List<String> excludeOperationIds,
        List<String> includeTags,
        List<String> excludeTags,
        boolean excludeDeprecated) {

    static OperationFilterResponse from(OperationFilter f) {
        if (f == null || f.isEmpty()) {
            return null;
        }
        return new OperationFilterResponse(f.includePaths(), f.excludePaths(), f.includeVerbs(),
                f.excludeVerbs(), f.includeOperationIds(), f.excludeOperationIds(), f.includeTags(),
                f.excludeTags(), f.excludeDeprecated());
    }
}
