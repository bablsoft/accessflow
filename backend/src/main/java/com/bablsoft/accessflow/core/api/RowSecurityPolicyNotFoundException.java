package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class RowSecurityPolicyNotFoundException extends RowSecurityPolicyException {

    public RowSecurityPolicyNotFoundException(UUID id) {
        super("Row security policy not found: " + id);
    }
}
