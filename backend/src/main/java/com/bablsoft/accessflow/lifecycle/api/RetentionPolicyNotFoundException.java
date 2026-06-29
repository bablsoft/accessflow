package com.bablsoft.accessflow.lifecycle.api;

import java.util.UUID;

public final class RetentionPolicyNotFoundException extends LifecycleException {

    public RetentionPolicyNotFoundException(UUID policyId) {
        super("Retention policy not found: " + policyId);
    }
}
