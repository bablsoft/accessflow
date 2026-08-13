package com.bablsoft.accessflow.scim.internal.web.admin;

import com.bablsoft.accessflow.scim.api.ScimTokenView;

import java.time.Instant;
import java.util.UUID;

record ScimTokenResponse(
        UUID id,
        String name,
        String tokenPrefix,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt) {

    static ScimTokenResponse from(ScimTokenView view) {
        return new ScimTokenResponse(
                view.id(),
                view.name(),
                view.tokenPrefix(),
                view.createdAt(),
                view.lastUsedAt(),
                view.revokedAt());
    }
}
