package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class ReviewDelegationNotFoundException extends ReviewDelegationException {

    public ReviewDelegationNotFoundException(UUID id) {
        super("Review delegation not found: " + id);
    }
}
