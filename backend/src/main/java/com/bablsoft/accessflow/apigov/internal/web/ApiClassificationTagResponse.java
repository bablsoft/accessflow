package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationTagView;
import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.core.api.DataClassification;

import java.time.Instant;
import java.util.UUID;

public record ApiClassificationTagResponse(
        UUID id,
        UUID connectorId,
        String operationId,
        String fieldRef,
        ApiMaskingMatcherType matcherType,
        DataClassification classification,
        String note,
        Instant createdAt,
        Instant updatedAt) {

    public static ApiClassificationTagResponse from(ApiConnectorClassificationTagView view) {
        return new ApiClassificationTagResponse(
                view.id(),
                view.connectorId(),
                view.operationId(),
                view.fieldRef(),
                view.matcherType(),
                view.classification(),
                view.note(),
                view.createdAt(),
                view.updatedAt());
    }
}
