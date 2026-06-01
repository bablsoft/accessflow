package com.bablsoft.accessflow.access.api;

/**
 * Thrown when the requested duration is not a parseable ISO-8601 period or falls outside the
 * configured {@code accessflow.access.min-duration} / {@code max-duration} bounds.
 */
public final class InvalidAccessDurationException extends AccessException {

    public InvalidAccessDurationException(String message) {
        super(message);
    }
}
