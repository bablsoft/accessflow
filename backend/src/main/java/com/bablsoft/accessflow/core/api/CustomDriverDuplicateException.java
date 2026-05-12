package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class CustomDriverDuplicateException extends RuntimeException {

    private final UUID existingDriverId;
    private final String jarSha256;

    public CustomDriverDuplicateException(UUID existingDriverId, String jarSha256) {
        super("A JDBC driver with SHA-256 " + jarSha256
                + " is already registered (id=" + existingDriverId + ")");
        this.existingDriverId = existingDriverId;
        this.jarSha256 = jarSha256;
    }

    public UUID existingDriverId() {
        return existingDriverId;
    }

    public String jarSha256() {
        return jarSha256;
    }
}
