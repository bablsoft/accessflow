package com.bablsoft.accessflow.access.api;

import java.util.UUID;

public final class AccessRequestNotFoundException extends AccessException {

    public AccessRequestNotFoundException(UUID accessRequestId) {
        super("Access request not found: " + accessRequestId);
    }
}
