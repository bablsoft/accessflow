package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of an uploaded schema document (raw content is not echoed back in lists).
 * {@code operationCount} is the post-filter (kept) count surfaced everywhere; {@code totalOperationCount}
 * is the number of operations the document defines before {@code operationFilter} is applied.
 * {@code detectedAuthMethod} is the auth scheme the uploaded document declared, when the format
 * carries one ({@code null} otherwise) — an admin hint only; no credential from the document is
 * ever read or stored.
 */
public record ApiSchemaView(
        UUID id,
        UUID connectorId,
        ApiSchemaType schemaType,
        String sourceUrl,
        int operationCount,
        int totalOperationCount,
        OperationFilter operationFilter,
        ApiAuthMethod detectedAuthMethod,
        Instant createdAt) {
}
