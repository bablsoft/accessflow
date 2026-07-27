package com.bablsoft.accessflow.discovery.api;

/**
 * Base type for discovery-module domain exceptions. Concrete subclasses are mapped to
 * {@code ProblemDetail} responses by the discovery web layer.
 */
public abstract class DiscoveryException extends RuntimeException {

    protected DiscoveryException(String message) {
        super(message);
    }
}
