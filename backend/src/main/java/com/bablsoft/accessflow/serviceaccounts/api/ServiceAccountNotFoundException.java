package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.UUID;

/** Unknown service account id, another organization, or a human user (#871) — 404. */
public final class ServiceAccountNotFoundException extends RuntimeException {

    private final UUID serviceAccountId;

    public ServiceAccountNotFoundException(UUID serviceAccountId) {
        super("Service account not found: " + serviceAccountId);
        this.serviceAccountId = serviceAccountId;
    }

    public UUID serviceAccountId() {
        return serviceAccountId;
    }
}
