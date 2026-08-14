package com.bablsoft.accessflow.scim.api;

import java.time.Instant;
import java.util.UUID;

/** A SCIM bearer token's metadata (#621) — never the raw token or its hash. */
public record ScimTokenView(
        UUID id,
        String name,
        String tokenPrefix,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt
) {
}
