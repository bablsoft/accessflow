package com.bablsoft.accessflow.serviceaccounts.api;

import java.util.UUID;

/** The owner is unknown, inactive, in another organization or not a HUMAN principal (#871) — 422. */
public final class ServiceAccountOwnerInvalidException extends RuntimeException {

    private final UUID ownerUserId;

    public ServiceAccountOwnerInvalidException(UUID ownerUserId) {
        super("Invalid service account owner: " + ownerUserId);
        this.ownerUserId = ownerUserId;
    }

    public UUID ownerUserId() {
        return ownerUserId;
    }
}
