package com.bablsoft.accessflow.ai.api;

import java.util.List;

/**
 * A stored conversation and every message in it, oldest first (AF-904) — what a client needs to
 * re-open a conversation the user had earlier.
 *
 * @param session  the conversation
 * @param messages its messages in the order they happened
 */
public record HelpChatConversationView(HelpChatSessionView session,
                                       List<HelpChatMessageView> messages) {

    public HelpChatConversationView {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
