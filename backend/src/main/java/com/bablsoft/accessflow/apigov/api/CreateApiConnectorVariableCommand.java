package com.bablsoft.accessflow.apigov.api;

/**
 * Command to create a connector variable (AF-613). Which fields are required, optional or forbidden
 * depends on {@code kind} — see {@link ApiVariableKind}; the admin service rejects a bad combination
 * with an {@link IllegalApiConnectorVariableException}.
 *
 * <p>{@code secret} is the raw shared key (required for {@code HMAC}, forbidden otherwise); it is
 * AES-256-GCM encrypted before persistence and never read back.
 */
public record CreateApiConnectorVariableCommand(
        String name,
        ApiVariableKind kind,
        String expression,
        ApiVariableAlgorithm algorithm,
        ApiVariableEncoding encoding,
        String secret,
        String target,
        Boolean overridable,
        String description,
        Integer sortOrder) {
}
