package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftFindingView;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApiSchemaDriftFindingResponse(
        UUID id,
        UUID schemaId,
        UUID revisionId,
        ApiSchemaDriftKind kind,
        String operationId,
        ApiOperationResponse before,
        ApiOperationResponse after,
        int affectedGrantCount,
        List<UUID> affectedUserGrantIds,
        List<UUID> affectedGroupGrantIds,
        ApiSchemaDriftStatus status,
        Instant createdAt,
        Instant resolvedAt,
        UUID resolvedBy) {

    static ApiSchemaDriftFindingResponse from(ApiSchemaDriftFindingView v) {
        return new ApiSchemaDriftFindingResponse(v.id(), v.schemaId(), v.revisionId(), v.kind(),
                v.operationId(), v.before() == null ? null : ApiOperationResponse.from(v.before()),
                v.after() == null ? null : ApiOperationResponse.from(v.after()), v.affectedGrantCount(),
                v.affectedUserGrantIds(), v.affectedGroupGrantIds(), v.status(), v.createdAt(),
                v.resolvedAt(), v.resolvedBy());
    }
}
