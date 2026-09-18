package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.UUID;

/** The named human is unknown, inactive, in another organization or not a HUMAN principal (#874) — 422. */
public final class ServiceAccountDelegationPrincipalInvalidException extends RuntimeException {

    private final UUID principalUserId;

    public ServiceAccountDelegationPrincipalInvalidException(UUID principalUserId) {
        super("Invalid delegated principal: " + principalUserId);
        this.principalUserId = principalUserId;
    }

    public UUID principalUserId() {
        return principalUserId;
    }
}
