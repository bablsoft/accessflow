package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when the help agent is enabled but the bundled documentation corpus could not be loaded from
 * the classpath — missing, empty, checksum-mismatched, or written to a bundle format newer than this
 * build understands.
 *
 * <p>This exists because loading the corpus deliberately does <em>not</em> fail the application
 * context: an install that never enables the help agent should not refuse to start over a resource it
 * will never read. The cost of that choice is that the failure has to surface somewhere an admin will
 * see it, and the enable path is that place. Resolved by the AI module's handler to HTTP 400
 * {@code HELP_CORPUS_MISSING}.
 */
public class HelpCorpusUnavailableException extends RuntimeException {

    private final String messageKey;

    public HelpCorpusUnavailableException(String messageKey, String loadError) {
        // The load error is diagnostic only and never reaches a response: it names a classpath
        // resource and a digest, which tells an admin nothing and an attacker a little.
        super(messageKey + ": " + loadError);
        this.messageKey = messageKey;
    }

    /** The localized detail the handler resolves — never {@link #getMessage()}, which is internal. */
    public String messageKey() {
        return messageKey;
    }
}
