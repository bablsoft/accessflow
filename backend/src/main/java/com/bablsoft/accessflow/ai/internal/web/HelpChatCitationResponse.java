package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.HelpChatCitation;

/**
 * One documentation section an answer cited.
 *
 * <p>{@code url} is the only link a client may render, and it was resolved server-side from the chunk
 * the model was actually given — never parsed out of the answer text (epic AF-899 decision 6). A
 * client renders {@code content} as plain text with auto-linking off and links exclusively from here.
 */
record HelpChatCitationResponse(
        int index,
        String chunkId,
        String title,
        String section,
        String anchor,
        String url) {

    static HelpChatCitationResponse from(HelpChatCitation citation) {
        return new HelpChatCitationResponse(citation.index(), citation.chunkId(), citation.title(),
                citation.section(), citation.anchor(), citation.url());
    }
}
