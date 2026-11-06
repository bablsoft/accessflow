package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiSchemaRefreshResult;

import java.util.UUID;

public record ApiSchemaRefreshResponse(UUID revisionId, int findingCount, boolean unchanged) {

    static ApiSchemaRefreshResponse from(ApiSchemaRefreshResult r) {
        return new ApiSchemaRefreshResponse(r.revisionId(), r.findingCount(), r.unchanged());
    }
}
