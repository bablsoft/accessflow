package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.DataClassification;

import java.time.Instant;
import java.util.UUID;

public record ApiConnectorClassificationTagView(
        UUID id,
        UUID connectorId,
        String operationId,
        String fieldRef,
        ApiMaskingMatcherType matcherType,
        DataClassification classification,
        String note,
        Instant createdAt,
        Instant updatedAt) {
}
