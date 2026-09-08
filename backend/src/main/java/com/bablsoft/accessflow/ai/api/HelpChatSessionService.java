package com.bablsoft.accessflow.ai.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.UUID;

/**
 * Stores help conversations so a user can come back to one (AF-904, epic AF-899).
 *
 * <p>Deliberately separate from {@link HelpChatService}, which answers a question and keeps nothing.
 * A caller runs the two in sequence: create or reuse a session, ask, then append the turn. Splitting
 * them is what lets a caller answer without storing — and, more importantly, keeps a provider call
 * that takes seconds outside the transaction that writes the transcript.
 *
 * <p>Every method is scoped to {@code (organizationId, userId)}. A conversation is private to the
 * person who had it: there is no cross-user read, not even for an admin, because the agent is a
 * documentation reader and its transcripts are not an audit surface.
 *
 * <p>Transcripts do not live forever. {@code help_agent_config.retention_days} bounds them and
 * {@code HelpChatRetentionJob} enforces it, so a session id that worked last quarter may simply be
 * gone.
 */
public interface HelpChatSessionService {

    /**
     * Starts an empty conversation. The title is filled in from the first question appended to it,
     * so nothing has to be named before it is used.
     */
    HelpChatSessionView createSession(UUID organizationId, UUID userId);

    /**
     * The user's conversations, most recently active first, without their messages.
     *
     * <p>Ordering falls back to when a session was created for one that was never used, so an empty
     * conversation someone opened and abandoned still sorts sensibly rather than to the bottom.
     */
    PageResponse<HelpChatSessionView> listSessions(UUID organizationId, UUID userId,
                                                   PageRequest pageRequest);

    /**
     * Appends one completed turn — the question and the answer — and advances the session's
     * counters, all in one transaction.
     *
     * @throws HelpChatSessionNotFoundException  no such conversation for this user
     * @throws HelpChatQuestionRequiredException the question is blank
     */
    HelpChatTurnView appendTurn(AppendHelpChatTurnCommand command);

    /**
     * The conversation and every message in it, oldest first.
     *
     * @throws HelpChatSessionNotFoundException no such conversation for this user
     */
    HelpChatConversationView loadConversation(UUID organizationId, UUID userId, UUID sessionId);

    /**
     * Deletes a conversation and its messages.
     *
     * @throws HelpChatSessionNotFoundException no such conversation for this user
     */
    void deleteSession(UUID organizationId, UUID userId, UUID sessionId);
}
