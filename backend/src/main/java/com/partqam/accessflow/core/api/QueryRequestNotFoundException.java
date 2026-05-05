package com.partqam.accessflow.core.api;

import java.util.UUID;

public final class QueryRequestNotFoundException extends RuntimeException {

    public QueryRequestNotFoundException(UUID queryRequestId) {
        super("Query request not found: " + queryRequestId);
    }
}
