package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-facing view of a connector variable (AF-613). The stored secret is never exposed — only
 * {@code hasSecret} reports whether one is configured, mirroring the connector credential rule.
 */
public record ApiConnectorVariableView(
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
}
