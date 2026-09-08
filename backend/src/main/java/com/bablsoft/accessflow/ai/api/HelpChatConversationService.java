package com.bablsoft.accessflow.ai.api;

import java.util.UUID;

/**
 * Answers a question inside a stored conversation, and reports whether the agent can answer at all
 * (AF-905).
 *
 * <p>The two halves of the feature are deliberately separate services — {@link HelpChatService}
 * answers and keeps nothing, {@link HelpChatSessionService} stores and calls no model — and something
 * has to run them in order. This is that something: load the conversation so far, answer, then append
 * the completed turn. Keeping the sequence here rather than in a controller is what stops the model
 * call from happening inside the transaction that writes the transcript.
 */
public interface HelpChatConversationService {

    /**
     * Whether this organization's agent can answer right now. Never throws for a disabled or
     * unconfigured agent — that is the answer.
     */
    HelpChatAvailabilityView availability(UUID organizationId);

    /**
     * Answers one question in an existing conversation and stores the turn.
     *
     * @throws HelpChatSessionNotFoundException  no such conversation for this user
     * @throws HelpChatQuestionRequiredException the question is blank
     * @throws HelpChatUnavailableException      the organization's agent is off or unbound
     * @throws AiRateLimitExceededException      the organization or the user is over their limit
     * @throws AiBudgetExceededException         the organization is over its monthly token budget
     * @throws AiAnalysisException               the provider call failed
     */
    HelpChatTurnView ask(AskHelpChatCommand command);
}
