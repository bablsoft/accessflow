package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class MaskingPolicyNotFoundException extends MaskingPolicyException {

    public MaskingPolicyNotFoundException(UUID id) {
        super("Masking policy not found: " + id);
    }
}
