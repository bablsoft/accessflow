package com.bablsoft.accessflow.ai.internal.help;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * A rendered help-chat turn, ready to hand to the model: the system preamble (instructions plus the
 * documentation context block) and the conversation itself, oldest first, ending with the current
 * question.
 *
 * <p>The two are kept apart rather than pre-merged because the preamble is re-rendered from scratch
 * on every turn — it carries the sections retrieved for <em>this</em> question — while the
 * conversation is replayed history. Merging them would make it possible for a stored turn to end up
 * where a system instruction belongs.
 *
 * @param systemPreamble    the system message content
 * @param conversation      user/assistant messages, oldest first, ending with the current question
 * @param citableChunks     the chunks the preamble numbered, in the order it numbered them; empty in
 *                          quick-reference mode, where there is nothing citable
 */
record HelpChatPrompt(String systemPreamble, List<Message> conversation,
                      List<RetrievedChunk> citableChunks) {
}
