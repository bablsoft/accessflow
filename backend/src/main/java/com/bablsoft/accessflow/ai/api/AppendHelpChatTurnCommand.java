package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * One completed help turn to store: the question that was asked and the answer that came back
 * (AF-904).
 *
 * <p>A turn is appended as a unit. Storing the question without the answer would leave a transcript
 * that reads as though the agent ignored someone, so both messages are written in one transaction
 * together with the session's counters.
 *
 * @param organizationId owning organization; scopes the session lookup
 * @param userId         the person who asked; a session belonging to anyone else is not found
 * @param sessionId      the conversation to append to
 * @param question       what the user asked, as it should be stored
 * @param answer         what the agent answered, including the server-resolved citations
 * @param corpusVersion  documentation revision that produced the answer, or {@code null} when the
 *                       answer did not come from the indexed corpus
 * @param latencyMs      how long the turn took end to end, or {@code null} when not measured
 */
public record AppendHelpChatTurnCommand(
        UUID organizationId,
        UUID userId,
        UUID sessionId,
        String question,
        HelpChatAnswer answer,
        String corpusVersion,
        Integer latencyMs) {
}
