package com.bablsoft.accessflow.security.api;

/**
 * Thrown when a step-up credential (password or TOTP code) fails to verify. Maps to HTTP 401.
 */
public class StepUpVerificationException extends RuntimeException {

    public StepUpVerificationException() {
        super("Step-up verification failed");
    }
}
