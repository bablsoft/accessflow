package com.bablsoft.accessflow.access.api;

import java.util.UUID;

public final class AccessRequestNotPendingException extends AccessException {

    private final transient AccessGrantStatus currentStatus;

    public AccessRequestNotPendingException(UUID accessRequestId, AccessGrantStatus currentStatus) {
        super("Access request " + accessRequestId + " is not pending review (status="
                + currentStatus + ")");
        this.currentStatus = currentStatus;
    }

    public AccessGrantStatus currentStatus() {
        return currentStatus;
    }
}
