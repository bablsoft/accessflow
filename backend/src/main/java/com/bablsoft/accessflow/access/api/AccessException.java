package com.bablsoft.accessflow.access.api;

/**
 * Base type for access-module domain exceptions. Concrete subclasses are mapped to
 * {@code ProblemDetail} responses by the access web layer.
 */
public abstract class AccessException extends RuntimeException {

    protected AccessException(String message) {
        super(message);
    }
}
