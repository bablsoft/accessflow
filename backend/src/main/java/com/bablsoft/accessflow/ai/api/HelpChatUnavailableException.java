package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when a help question arrives for an organization whose agent cannot answer it — no
 * configuration saved, the agent switched off, or the bound {@code ai_config} deleted out from under
 * it, which leaves the row enabled but inert ({@code ON DELETE SET NULL}).
 *
 * <p>Carries a message key rather than authored English, resolved against the caller's locale by the
 * handler that maps it to a response.
 */
public class HelpChatUnavailableException extends RuntimeException {

    private final String messageKey;

    public HelpChatUnavailableException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    /** The localized detail the handler resolves — never {@link #getMessage()}, which is internal. */
    public String messageKey() {
        return messageKey;
    }
}
