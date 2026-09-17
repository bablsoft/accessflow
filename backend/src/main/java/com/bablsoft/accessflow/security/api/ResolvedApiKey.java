package com.bablsoft.accessflow.security.api;

import java.util.UUID;

/**
 * An API key that matched an active, unexpired, non-revoked {@code api_keys} row (#869):
 * {@code apiKeyId} is the row's id, {@code userId} its owner. Carrying the key id (rather than
 * only the owner) is what lets later features attribute a request to the credential that made
 * it — the owner alone cannot tell two keys of the same service account apart.
 */
public record ResolvedApiKey(UUID apiKeyId, UUID userId) {}
