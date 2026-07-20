package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableView;
import com.bablsoft.accessflow.apigov.api.ApiVariableAlgorithm;
import com.bablsoft.accessflow.apigov.api.ApiVariableEncoding;
import com.bablsoft.accessflow.apigov.api.ApiVariableKind;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin view of a connector variable (AF-613). Carries no secret — only {@code hasSecret} — matching
 * the rule that a connector's stored credentials are never returned by a GET.
 */
public record ApiConnectorVariableResponse(
        UUID id,
        UUID connectorId,
        String name,
        ApiVariableKind kind,
        String expression,
        ApiVariableAlgorithm algorithm,
        ApiVariableEncoding encoding,
        boolean hasSecret,
        String target,
        boolean overridable,
        String description,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt) {

    public static ApiConnectorVariableResponse from(ApiConnectorVariableView v) {
        return new ApiConnectorVariableResponse(v.id(), v.connectorId(), v.name(), v.kind(),
                v.expression(), v.algorithm(), v.encoding(), v.hasSecret(), v.target(),
                v.overridable(), v.description(), v.sortOrder(), v.createdAt(), v.updatedAt());
    }
}
