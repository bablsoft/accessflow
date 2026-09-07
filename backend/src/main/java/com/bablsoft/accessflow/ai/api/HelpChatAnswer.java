package com.bablsoft.accessflow.ai.api;

import java.util.List;

/**
 * What the help agent answered, plus the accounting the caller has to record (AF-903).
 *
 * <p>{@code content} is plain text and is rendered as plain text — no markup, no auto-linking. The
 * only links a client may show are the ones in {@code citations}, which the server resolved from the
 * retrieved corpus (epic AF-899 decision 6).
 *
 * @param content           the answer text, with {@code [n]} markers left in place so a client can
 *                          align them with {@code citations}
 * @param citations         the sections cited, in the order they first appear in {@code content} —
 *                          the order a reader meets them, not ascending index. Empty when the model
 *                          cited nothing it was actually given
 * @param retrievalUsed     {@code false} when the answer came from the bundled quick-reference block
 *                          instead of retrieved sections — the supported degraded mode, in which
 *                          there is nothing to cite
 * @param model             the provider's model name as reported on the response, or empty
 * @param promptTokens      prompt tokens the provider reported, or 0
 * @param completionTokens  completion tokens the provider reported, or 0
 */
public record HelpChatAnswer(
        String content,
        List<HelpChatCitation> citations,
        boolean retrievalUsed,
        String model,
        int promptTokens,
        int completionTokens) {

    public HelpChatAnswer {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** Total tokens this turn spent, which is what the monthly AI budget counts. */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
