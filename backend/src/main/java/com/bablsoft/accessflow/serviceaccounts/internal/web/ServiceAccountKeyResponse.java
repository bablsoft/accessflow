package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyView;

import java.time.Instant;
import java.util.UUID;

public record ServiceAccountKeyResponse(
        UUID id,
        String name,
        String keyPrefix,
        boolean bootstrapDeclared,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant revokedAt,
        String applicationName
) {
    public static ServiceAccountKeyResponse from(ServiceAccountKeyView view) {
        return new ServiceAccountKeyResponse(view.id(), view.name(), view.keyPrefix(), view.bootstrapDeclared(),
                view.createdAt(), view.lastUsedAt(), view.expiresAt(), view.revokedAt(),
                view.applicationName());
    }
}
