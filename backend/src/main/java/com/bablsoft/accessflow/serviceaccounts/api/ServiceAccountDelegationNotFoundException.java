package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.UUID;

/** Unknown delegation id, another organization, or not the caller's own grant (#874) — 404. */
public final class ServiceAccountDelegationNotFoundException extends RuntimeException {

    private final UUID delegationId;

    public ServiceAccountDelegationNotFoundException(UUID delegationId) {
        super("Service account delegation not found: " + delegationId);
        this.delegationId = delegationId;
    }

    public UUID delegationId() {
        return delegationId;
    }
}
