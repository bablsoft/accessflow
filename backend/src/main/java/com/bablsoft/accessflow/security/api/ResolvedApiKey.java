package com.bablsoft.accessflow.security.api;

import java.util.UUID;

/**
 * An API key that matched an active, unexpired, non-revoked {@code api_keys} row (#869):
 * {@code apiKeyId} is the row's id, {@code userId} its owner. Carrying the key id (rather than
 * only the owner) is what lets later features attribute a request to the credential that made
 * it — the owner alone cannot tell two keys of the same service account apart.
 * {@code applicationName} (#938) is the calling application stored on the key, or {@code null}.
 */
public record ResolvedApiKey(UUID apiKeyId, UUID userId, String applicationName) {

    /** Backward-compatible constructor for a key without an application name. */
    public ResolvedApiKey(UUID apiKeyId, UUID userId) {
        this(apiKeyId, userId, null);
    }
}
