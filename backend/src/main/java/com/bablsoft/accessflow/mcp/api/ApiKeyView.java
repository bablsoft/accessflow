package com.bablsoft.accessflow.mcp.api;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyView(
        UUID id,
        UUID userId,
        UUID organizationId,
        String name,
        String keyPrefix,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt
) {}
