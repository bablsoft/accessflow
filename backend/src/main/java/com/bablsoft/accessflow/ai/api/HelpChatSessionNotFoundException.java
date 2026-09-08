package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * Thrown when a help conversation does not exist for the asking user (AF-904).
 *
 * <p>Deliberately the same failure whether the session was never created, was deleted, aged out of
 * retention, or belongs to somebody else: a transcript is private to the person who had it, and
 * distinguishing "not yours" from "not there" would let one user probe another's session ids.
 */
public class HelpChatSessionNotFoundException extends RuntimeException {

    private final UUID sessionId;

    public HelpChatSessionNotFoundException(UUID sessionId) {
        super("Help chat session not found: " + sessionId);
        this.sessionId = sessionId;
    }

    public UUID sessionId() {
        return sessionId;
    }
}
