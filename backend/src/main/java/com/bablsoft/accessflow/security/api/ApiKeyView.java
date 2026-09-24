package com.bablsoft.accessflow.security.api;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code bootstrapDeclared} (#871) marks the one key the bootstrap reconciler declared for a
 * service account — the key an admin cannot revoke or rotate, because a changed reconcile would
 * reactivate it. {@code applicationName} (#938) is the calling application the key identifies, or
 * {@code null}.
 */
public record ApiKeyView(
        UUID id,
        UUID userId,
        UUID organizationId,
        String name,
        String keyPrefix,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt,
        boolean bootstrapDeclared,
        String applicationName
) {

    /** Backward-compatible constructor without the #938 application name. */
    public ApiKeyView(UUID id, UUID userId, UUID organizationId, String name, String keyPrefix,
                      Instant createdAt, Instant lastUsedAt, Instant expiresAt, Instant revokedAt,
                      boolean bootstrapDeclared) {
        this(id, userId, organizationId, name, keyPrefix, createdAt, lastUsedAt, expiresAt,
                revokedAt, bootstrapDeclared, null);
    }
}
