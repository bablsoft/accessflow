package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.UUID;

/** Read view of an uploaded schema document (raw content is not echoed back in lists). */
public record ApiSchemaView(
        UUID id,
        UUID connectorId,
        ApiSchemaType schemaType,
        String sourceUrl,
        int operationCount,
        Instant createdAt) {
}
