package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.UUID;

/** A fetched schema revision (raw content is not echoed). {@code operationCount} is the full, pre-filter count. */
public record ApiSchemaRevisionView(
        UUID id,
        UUID schemaId,
        String contentHash,
        int operationCount,
        Instant fetchedAt,
        Instant lastSeenAt,
        Instant acceptedAt,
        UUID acceptedBy) {
}
