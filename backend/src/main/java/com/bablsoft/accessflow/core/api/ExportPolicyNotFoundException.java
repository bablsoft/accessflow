package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class ExportPolicyNotFoundException extends ExportPolicyException {

    public ExportPolicyNotFoundException(UUID id) {
        super("Export policy not found: " + id);
    }
}
