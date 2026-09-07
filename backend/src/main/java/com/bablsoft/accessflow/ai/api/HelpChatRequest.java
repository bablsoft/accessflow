package com.bablsoft.accessflow.ai.api;

import java.util.List;
import java.util.UUID;

/**
 * One turn of a help conversation as the caller supplies it (AF-903). Everything here is treated as
 * untrusted client input: the question and each history entry are truncated to the organization's
 * {@code max_question_chars} and the history is capped at its {@code max_history_turns} before any
 * of it reaches a model, so a client that ignores its own limits cannot inflate a prompt.
 *
 * @param organizationId the asking user's organization; scopes the configuration and both rate limits
 * @param userId         the asking user; scopes the per-user rate limit
 * @param question       what the user asked
 * @param history        prior turns, oldest first, excluding {@code question}
 * @param routeLabel     the app route the user is looking at, as a human label ("Review queue") and
 *                       never a URL with a query string. Included in the prompt only when the
 *                       organization has {@code send_user_context} on
 * @param permissions    the user's permission names, for answers that depend on what they may do.
 *                       Same {@code send_user_context} gate
 * @param language       the user's language tag, so the agent answers in it from English sources
 */
public record HelpChatRequest(
        UUID organizationId,
        UUID userId,
        String question,
        List<HelpChatMessage> history,
        String routeLabel,
        List<String> permissions,
        String language) {

    public HelpChatRequest {
        history = history == null ? List.of() : List.copyOf(history);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
