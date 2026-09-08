package com.bablsoft.accessflow.ai.api;

/**
 * Thrown when a help turn arrives with nothing to answer — an empty question, or one that was only
 * whitespace once the server trimmed it.
 *
 * <p>Separate from {@link HelpChatUnavailableException} because it is a different kind of failure:
 * nothing is wrong with the organization's configuration, the caller simply sent no question. It maps
 * to 400 rather than 409, and it is refused before either rate-limit counter is touched — a client
 * bug should not cost a user their place in the window.
 */
public class HelpChatQuestionRequiredException extends RuntimeException {

    public HelpChatQuestionRequiredException() {
        super("A help question must not be blank");
    }
}
