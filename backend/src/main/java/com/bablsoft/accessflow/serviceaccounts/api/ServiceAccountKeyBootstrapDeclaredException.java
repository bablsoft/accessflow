package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.UUID;

/** Revoke / rotate of the key the bootstrap reconciler declared (#871) — 409. A changed reconcile would reactivate it; rotate the secret at the bootstrap source and restart. */
public final class ServiceAccountKeyBootstrapDeclaredException extends RuntimeException {

    private final UUID apiKeyId;

    public ServiceAccountKeyBootstrapDeclaredException(UUID apiKeyId) {
        super("API key is bootstrap-declared: " + apiKeyId);
        this.apiKeyId = apiKeyId;
    }

    public UUID apiKeyId() {
        return apiKeyId;
    }
}
