package com.bablsoft.accessflow.security.api;

/**
 * Thrown when an action requires a valid step-up token but none was presented (or it was missing,
 * expired, or already consumed). Maps to HTTP 401.
 */
public class StepUpRequiredException extends RuntimeException {

    public StepUpRequiredException() {
        super("Step-up authentication required");
    }
}
