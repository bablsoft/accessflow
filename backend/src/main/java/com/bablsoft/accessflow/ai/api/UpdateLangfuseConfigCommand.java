package com.bablsoft.accessflow.ai.api;

/**
 * Mutable Langfuse-config fields. Each field is applied independently; {@code null} leaves the
 * stored value unchanged (partial update).
 *
 * <p>{@code secretKey} semantics:
 * <ul>
 *     <li>{@code null} — leave the existing ciphertext unchanged.</li>
 *     <li>literal {@code "********"} — leave the existing ciphertext unchanged.</li>
 *     <li>blank string — clear the stored secret key.</li>
 *     <li>any other value — encrypt and persist.</li>
 * </ul>
 */
public record UpdateLangfuseConfigCommand(
        Boolean enabled,
        String host,
        String publicKey,
        String secretKey,
        Boolean tracingEnabled,
        Boolean promptManagementEnabled) {

    public static final String MASKED_SECRET = "********";
}
