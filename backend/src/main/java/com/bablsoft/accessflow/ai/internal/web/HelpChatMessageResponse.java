package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.HelpChatMessageView;
import com.bablsoft.accessflow.ai.api.HelpChatRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One stored message of a conversation.
 *
 * <p>The provider model and the turn's token counts are on the stored view but deliberately not here:
 * they are AI-spend accounting an admin reads through the AI pages, and a help panel has no use for
 * them. {@code latencyMs} stays because a client may want to show how long an answer took.
 */
record HelpChatMessageResponse(
        UUID id,
        HelpChatRole role,
        String content,
        List<HelpChatCitationResponse> citations,
        String corpusVersion,
        Integer latencyMs,
        Instant createdAt) {

    static HelpChatMessageResponse from(HelpChatMessageView view) {
        return new HelpChatMessageResponse(view.id(), view.role(), view.content(),
                view.citations().stream().map(HelpChatCitationResponse::from).toList(),
                view.corpusVersion(), view.latencyMs(), view.createdAt());
    }
}
