package com.bablsoft.accessflow.apigov.api;

/**
 * Command to update a connector variable (AF-613).
 *
 * <p>{@code secret} is write-only and tri-state: {@code null} leaves the stored secret untouched, a
 * non-blank value replaces it, and {@code clearSecret} removes it. That mirrors how connector auth
 * credentials are edited — the current value is never sent to the client, so an unchanged field
 * cannot be round-tripped.
 */
public record UpdateApiConnectorVariableCommand(
        String name,
        ApiVariableKind kind,
        String expression,
        ApiVariableAlgorithm algorithm,
        ApiVariableEncoding encoding,
        String secret,
        Boolean clearSecret,
        String target,
        Boolean overridable,
        String description,
        Integer sortOrder) {
}
