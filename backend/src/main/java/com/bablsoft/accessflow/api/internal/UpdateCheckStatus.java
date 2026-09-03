package com.bablsoft.accessflow.api.internal;

/** Outcome of the most recent release update check. */
public enum UpdateCheckStatus {
    /** No successful check yet, the check is disabled, the build version is unknown, or the last check failed. */
    UNKNOWN,
    UP_TO_DATE,
    UPDATE_AVAILABLE
}
