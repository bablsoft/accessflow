package com.bablsoft.accessflow.access.api;

/**
 * Thrown on submission when a connector access request names operation ids that do not exist in
 * the connector's operation catalog. Maps to HTTP 422. The message is locale-resolved at the
 * throw site.
 */
public final class InvalidAccessOperationsException extends AccessException {

    public InvalidAccessOperationsException(String message) {
        super(message);
    }
}
