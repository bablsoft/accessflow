package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.UUID;

/** Rotate of an already-revoked key (#871) — 409; rotate a live key or issue a fresh one. */
public final class ServiceAccountKeyRevokedException extends RuntimeException {

    private final UUID apiKeyId;

    public ServiceAccountKeyRevokedException(UUID apiKeyId) {
        super("API key is revoked: " + apiKeyId);
        this.apiKeyId = apiKeyId;
    }

    public UUID apiKeyId() {
        return apiKeyId;
    }
}
