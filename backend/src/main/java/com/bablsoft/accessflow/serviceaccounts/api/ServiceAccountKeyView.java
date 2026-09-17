package com.bablsoft.accessflow.serviceaccounts.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One of a service account's API keys, raw secret never included (#871). {@code bootstrapDeclared}
 * marks the key the bootstrap reconciler declared — the one an admin can neither revoke nor rotate.
 */
public record ServiceAccountKeyView(
        UUID id,
        String name,
        String keyPrefix,
        boolean bootstrapDeclared,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt
) {
}
