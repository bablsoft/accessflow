package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class DataClassificationTagNotFoundException extends DataClassificationTagException {

    public DataClassificationTagNotFoundException(UUID id) {
        super("Data classification tag not found: " + id);
    }
}
