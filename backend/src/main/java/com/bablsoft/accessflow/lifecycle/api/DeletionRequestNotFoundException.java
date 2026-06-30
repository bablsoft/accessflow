package com.bablsoft.accessflow.lifecycle.api;

import java.util.UUID;

public final class DeletionRequestNotFoundException extends LifecycleException {

    public DeletionRequestNotFoundException(UUID requestId) {
        super("Deletion request not found: " + requestId);
    }
}
