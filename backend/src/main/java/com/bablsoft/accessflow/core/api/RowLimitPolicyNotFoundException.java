package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class RowLimitPolicyNotFoundException extends RowLimitPolicyException {

    public RowLimitPolicyNotFoundException(UUID id) {
        super("Row limit policy not found: " + id);
    }
}
