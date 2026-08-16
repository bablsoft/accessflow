package com.bablsoft.accessflow.core.api;

/**
 * A delegation that cannot be created as asked: delegating to yourself, to a user outside the
 * organization or no longer active, a scope reference that does not resolve, an inverted window, or
 * more active delegations than the configured cap allows.
 */
public final class IllegalReviewDelegationException extends ReviewDelegationException {

    public IllegalReviewDelegationException(String message) {
        super(message);
    }
}
