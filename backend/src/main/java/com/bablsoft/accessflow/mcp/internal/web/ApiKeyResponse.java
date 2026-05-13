package com.bablsoft.accessflow.mcp.internal.web;

import com.bablsoft.accessflow.mcp.api.ApiKeyView;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyResponse(
        UUID id,
        String name,
        String keyPrefix,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt
) {
    public static ApiKeyResponse from(ApiKeyView view) {
        return new ApiKeyResponse(
                view.id(),
                view.name(),
                view.keyPrefix(),
                view.createdAt(),
                view.lastUsedAt(),
                view.expiresAt(),
                view.revokedAt()
        );
    }
}
