package com.bablsoft.accessflow.lifecycle.api;

/**
 * Thrown when an action is attempted on a deletion request whose current status does not permit it
 * (an illegal state-machine transition). {@code currentStatus} is surfaced on the
 * {@code ProblemDetail}.
 */
public final class DeletionRequestInvalidStateException extends LifecycleException {

    private final transient ErasureStatus currentStatus;

    public DeletionRequestInvalidStateException(ErasureStatus currentStatus) {
        super("Deletion request is not in a state that allows this action: " + currentStatus);
        this.currentStatus = currentStatus;
    }

    public ErasureStatus currentStatus() {
        return currentStatus;
    }
}
