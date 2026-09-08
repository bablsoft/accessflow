package com.bablsoft.accessflow.ai.api;

/**
 * What one appended turn became once stored (AF-904): the session with its counters advanced, and
 * both messages with the ids a client needs to address them.
 *
 * @param session          the conversation after the turn
 * @param userMessage      the question as stored
 * @param assistantMessage the answer as stored, carrying the citations the server resolved
 */
public record HelpChatTurnView(HelpChatSessionView session,
                               HelpChatMessageView userMessage,
                               HelpChatMessageView assistantMessage) {
}
