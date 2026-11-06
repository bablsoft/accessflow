package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionView;

import java.time.Instant;
import java.util.UUID;

public record ApiSchemaRevisionResponse(
        UUID id,
        UUID schemaId,
        String contentHash,
        int operationCount,
        Instant fetchedAt,
        Instant lastSeenAt,
        Instant acceptedAt,
        UUID acceptedBy) {

    static ApiSchemaRevisionResponse from(ApiSchemaRevisionView v) {
        return new ApiSchemaRevisionResponse(v.id(), v.schemaId(), v.contentHash(), v.operationCount(),
                v.fetchedAt(), v.lastSeenAt(), v.acceptedAt(), v.acceptedBy());
    }
}
