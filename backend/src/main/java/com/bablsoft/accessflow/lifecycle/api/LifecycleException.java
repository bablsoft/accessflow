package com.bablsoft.accessflow.lifecycle.api;

/**
 * Base type for lifecycle-module domain exceptions. Concrete subclasses are mapped to
 * {@code ProblemDetail} responses by the lifecycle web layer.
 */
public abstract class LifecycleException extends RuntimeException {

    protected LifecycleException(String message) {
        super(message);
    }
}
