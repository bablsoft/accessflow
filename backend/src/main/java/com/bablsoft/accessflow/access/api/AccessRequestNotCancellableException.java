package com.bablsoft.accessflow.access.api;

import java.util.UUID;

public final class AccessRequestNotCancellableException extends AccessException {

    private final transient AccessGrantStatus currentStatus;

    public AccessRequestNotCancellableException(UUID accessRequestId, AccessGrantStatus currentStatus) {
        super("Access request " + accessRequestId + " cannot be cancelled (status="
                + currentStatus + ")");
        this.currentStatus = currentStatus;
    }

    public AccessGrantStatus currentStatus() {
        return currentStatus;
    }
}
