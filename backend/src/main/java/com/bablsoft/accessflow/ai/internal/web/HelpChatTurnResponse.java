package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.HelpChatTurnView;

/**
 * What one asked question became: the session with its counters advanced and both stored messages, so
 * a client can append the exchange without re-reading the conversation.
 */
record HelpChatTurnResponse(
        HelpChatSessionResponse session,
        HelpChatMessageResponse userMessage,
        HelpChatMessageResponse assistantMessage) {

    static HelpChatTurnResponse from(HelpChatTurnView view) {
        return new HelpChatTurnResponse(HelpChatSessionResponse.from(view.session()),
                HelpChatMessageResponse.from(view.userMessage()),
                HelpChatMessageResponse.from(view.assistantMessage()));
    }
}
