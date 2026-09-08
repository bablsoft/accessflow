package com.bablsoft.accessflow.ai.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One stored message of a help conversation (AF-904).
 *
 * <p>{@code citations} are replayed exactly as the server resolved them when the answer was produced
 * — never re-derived from {@code content} (epic AF-899 decision 6) — so a conversation reloaded a
 * year later still links to the sections it actually used. The accounting fields are what the
 * provider reported on that turn and are {@code null} on a user message, which cost nothing.
 *
 * @param id               the message
 * @param role             who wrote it
 * @param content          the text, with any {@code [n]} markers left in place
 * @param citations        the sections the answer cited, empty on a user message
 * @param corpusVersion    documentation revision that produced the answer, or {@code null}
 * @param model            provider model that produced the answer, or {@code null}
 * @param promptTokens     prompt tokens the provider reported, or {@code null}
 * @param completionTokens completion tokens the provider reported, or {@code null}
 * @param latencyMs        how long the turn took end to end, or {@code null}
 * @param createdAt        when the message was stored
 */
public record HelpChatMessageView(
        UUID id,
        HelpChatRole role,
        String content,
        List<HelpChatCitation> citations,
        String corpusVersion,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer latencyMs,
        Instant createdAt) {

    public HelpChatMessageView {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
