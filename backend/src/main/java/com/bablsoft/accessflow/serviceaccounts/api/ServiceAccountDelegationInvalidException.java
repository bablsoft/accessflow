package com.bablsoft.accessflow.serviceaccounts.api;

/** {@code expiresAt} is in the past (#874) — 422. */
public final class ServiceAccountDelegationInvalidException extends RuntimeException {

    public ServiceAccountDelegationInvalidException() {
        super("Delegation expiry must be in the future");
    }
}
