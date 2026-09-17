package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.UUID;

/** Unknown API key id, or a key not owned by that service account (#871) — 404. */
public final class ServiceAccountKeyNotFoundException extends RuntimeException {

    private final UUID apiKeyId;

    public ServiceAccountKeyNotFoundException(UUID apiKeyId) {
        super("Service account API key not found: " + apiKeyId);
        this.apiKeyId = apiKeyId;
    }

    public UUID apiKeyId() {
        return apiKeyId;
    }
}
