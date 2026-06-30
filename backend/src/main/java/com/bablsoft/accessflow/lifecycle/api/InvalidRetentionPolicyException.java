package com.bablsoft.accessflow.lifecycle.api;

/**
 * Thrown when a retention policy violates a business invariant: no target table or classification
 * tag, a PSEUDONYMIZE action without a transform, or an unparseable retention window. The
 * {@code reasonCode} drives the i18n message key on the {@code ProblemDetail}.
 */
public final class InvalidRetentionPolicyException extends LifecycleException {

    public enum Reason {
        NO_TARGET,
        TRANSFORM_REQUIRED,
        TRANSFORM_NOT_ALLOWED,
        INVALID_WINDOW
    }

    private final transient Reason reason;

    public InvalidRetentionPolicyException(Reason reason) {
        super("Invalid retention policy: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
