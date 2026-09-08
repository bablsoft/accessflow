package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.HelpChatConversationView;

import java.util.List;

/** A conversation and every message in it, oldest first. */
record HelpChatConversationResponse(
        HelpChatSessionResponse session,
        List<HelpChatMessageResponse> messages) {

    static HelpChatConversationResponse from(HelpChatConversationView view) {
        return new HelpChatConversationResponse(HelpChatSessionResponse.from(view.session()),
                view.messages().stream().map(HelpChatMessageResponse::from).toList());
    }
}
