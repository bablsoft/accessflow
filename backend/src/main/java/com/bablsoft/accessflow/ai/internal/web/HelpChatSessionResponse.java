package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.HelpChatSessionView;

import java.time.Instant;
import java.util.UUID;

/**
 * One help conversation in a list. The owning organization and user are not echoed: the only
 * conversations any caller can reach are their own, so repeating their identity back adds nothing.
 */
record HelpChatSessionResponse(
        UUID id,
        String title,
        int messageCount,
        Instant lastMessageAt,
        Instant createdAt) {

    static HelpChatSessionResponse from(HelpChatSessionView view) {
        return new HelpChatSessionResponse(view.id(), view.title(), view.messageCount(),
                view.lastMessageAt(), view.createdAt());
    }
}
