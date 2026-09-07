package com.bablsoft.accessflow.ai.api;

/**
 * One prior message of a help conversation, replayed to the model as context for the next question
 * (AF-903).
 *
 * @param role    who wrote it
 * @param content the message text; truncated server-side to the organization's
 *                {@code max_question_chars} before it reaches the model, whatever the client sends
 */
public record HelpChatMessage(HelpChatRole role, String content) {
}
