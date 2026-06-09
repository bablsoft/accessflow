package com.bablsoft.accessflow.core.api;

/**
 * Thrown when a connector id does not exist in the catalog (e.g. an install request for an
 * unknown connector). Mapped to HTTP 404 by the global exception handler.
 */
public final class ConnectorNotFoundException extends RuntimeException {

    private final String connectorId;

    public ConnectorNotFoundException(String connectorId) {
        super("Connector not found: " + connectorId);
        this.connectorId = connectorId;
    }

    public String connectorId() {
        return connectorId;
    }
}
