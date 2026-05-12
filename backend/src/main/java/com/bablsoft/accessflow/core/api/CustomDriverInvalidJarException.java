package com.bablsoft.accessflow.core.api;

/**
 * Thrown when an uploaded JAR cannot be loaded as a JDBC driver — typically because the
 * declared driver class is not found in the archive or does not implement
 * {@link java.sql.Driver}.
 */
public final class CustomDriverInvalidJarException extends RuntimeException {

    private final String driverClass;

    public CustomDriverInvalidJarException(String driverClass, String message) {
        super(message);
        this.driverClass = driverClass;
    }

    public CustomDriverInvalidJarException(String driverClass, String message, Throwable cause) {
        super(message, cause);
        this.driverClass = driverClass;
    }

    public String driverClass() {
        return driverClass;
    }
}
