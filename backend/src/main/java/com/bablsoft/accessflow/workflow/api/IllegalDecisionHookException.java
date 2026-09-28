package com.bablsoft.accessflow.workflow.api;

/**
 * A decision hook the service refuses to store — today, an endpoint URL that fails the scheme or
 * address check (#945). Carries a message key, resolved at the web layer. Mapped to HTTP 422.
 */
public final class IllegalDecisionHookException extends RuntimeException {

    private final String messageKey;

    public IllegalDecisionHookException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
