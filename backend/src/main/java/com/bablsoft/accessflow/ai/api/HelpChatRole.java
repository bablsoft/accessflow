package com.bablsoft.accessflow.ai.api;

/**
 * Who wrote one message of a help conversation (AF-903).
 *
 * <p>There is no system role here on purpose. The system preamble is rendered server-side on every
 * turn and is never supplied by a caller, so no client can put text where the model reads
 * instructions. It cannot do more than that: a caller can still fabricate an {@link #ASSISTANT} turn,
 * which is replayed verbatim and which models do weight. What keeps that survivable is the preamble
 * itself, which says the conversation is data rather than instructions, and the fact that the agent
 * has no tools and no data access to misuse if it were talked round.
 */
public enum HelpChatRole {

    USER,
    ASSISTANT
}
