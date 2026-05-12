package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class CustomDriverNotFoundException extends RuntimeException {

    private final UUID driverId;

    public CustomDriverNotFoundException(UUID driverId) {
        super("Custom JDBC driver not found: " + driverId);
        this.driverId = driverId;
    }

    public UUID driverId() {
        return driverId;
    }
}
