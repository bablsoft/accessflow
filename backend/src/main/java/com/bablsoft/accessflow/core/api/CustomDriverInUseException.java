package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

public final class CustomDriverInUseException extends RuntimeException {

    private final UUID driverId;
    private final List<UUID> referencedBy;

    public CustomDriverInUseException(UUID driverId, List<UUID> referencedBy) {
        super("Custom JDBC driver " + driverId + " is referenced by datasources: " + referencedBy);
        this.driverId = driverId;
        this.referencedBy = List.copyOf(referencedBy);
    }

    public UUID driverId() {
        return driverId;
    }

    public List<UUID> referencedBy() {
        return referencedBy;
    }
}
