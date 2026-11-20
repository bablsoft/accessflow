package com.bablsoft.accessflow.core.api;

public final class DatasourceConnectionTestException extends DatasourceAdminException {

    private final ConnectionFailureReason reason;

    public DatasourceConnectionTestException(String message) {
        this(message, ConnectionFailureReason.UNKNOWN);
    }

    public DatasourceConnectionTestException(String message, ConnectionFailureReason reason) {
        super(message);
        this.reason = reason == null ? ConnectionFailureReason.UNKNOWN : reason;
    }

    public ConnectionFailureReason reason() {
        return reason;
    }
}
