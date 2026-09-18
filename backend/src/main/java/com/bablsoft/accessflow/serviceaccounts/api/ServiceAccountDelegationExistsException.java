package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.UUID;

/** A live grant for this (service account, principal) pair already exists (#874) — 409. */
public final class ServiceAccountDelegationExistsException extends RuntimeException {

    private final UUID serviceAccountUserId;
    private final UUID principalUserId;

    public ServiceAccountDelegationExistsException(UUID serviceAccountUserId, UUID principalUserId) {
        super("Delegation already exists: " + serviceAccountUserId + " for " + principalUserId);
        this.serviceAccountUserId = serviceAccountUserId;
        this.principalUserId = principalUserId;
    }

    public UUID serviceAccountUserId() {
        return serviceAccountUserId;
    }

    public UUID principalUserId() {
        return principalUserId;
    }
}
