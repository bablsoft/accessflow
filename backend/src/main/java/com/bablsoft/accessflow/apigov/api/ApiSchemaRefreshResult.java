package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

/** Outcome of a refresh: {@code revisionId} is {@code null} when the fetched catalog was {@code unchanged}. */
public record ApiSchemaRefreshResult(UUID revisionId, int findingCount, boolean unchanged) {

    public static ApiSchemaRefreshResult unchangedResult() {
        return new ApiSchemaRefreshResult(null, 0, true);
    }
}
