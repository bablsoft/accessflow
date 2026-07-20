package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.ApiSchemaView;

import java.time.Instant;
import java.util.UUID;

public record ApiSchemaResponse(
        UUID id,
        ApiSchemaType schemaType,
        String sourceUrl,
        int operationCount,
        int totalOperationCount,
        OperationFilterResponse operationFilter,
        ApiAuthMethod detectedAuthMethod,
        Instant createdAt) {

    static ApiSchemaResponse from(ApiSchemaView v) {
        return new ApiSchemaResponse(v.id(), v.schemaType(), v.sourceUrl(), v.operationCount(),
                v.totalOperationCount(), OperationFilterResponse.from(v.operationFilter()),
                v.detectedAuthMethod(), v.createdAt());
    }
}
