package com.bablsoft.accessflow.serviceaccounts.api;

/** Read-time state of a delegated-principal grant (#874); never stored. */
public enum ServiceAccountDelegationStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
}
