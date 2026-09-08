package com.bablsoft.accessflow.ai.api;

import java.util.List;
import java.util.UUID;

/**
 * One question asked inside a stored conversation (AF-905) — what the HTTP layer hands the
 * orchestrator that answers it and appends the turn.
 *
 * <p>Only {@code question} and {@code routeLabel} come from the request body. The identity fields
 * come from the authenticated principal and {@code permissions} / {@code language} from the caller's
 * token and locale, never from the body: a client that could name its own permissions could describe
 * itself to the model as an administrator.
 *
 * @param organizationId the asking user's organization
 * @param userId         the asking user; a session belonging to anyone else is not found
 * @param sessionId      the conversation to append the turn to
 * @param question       what the user asked
 * @param routeLabel     the screen the user is on, as a human label ("Review queue"). Sanitized
 *                       server-side before it reaches a model — a path with an id in it is dropped
 * @param permissions    the caller's permission names, resolved from the principal
 * @param language       the caller's language tag, so the agent answers in it from English sources
 */
public record AskHelpChatCommand(
        UUID organizationId,
        UUID userId,
        UUID sessionId,
        String question,
        String routeLabel,
        List<String> permissions,
        String language) {

    public AskHelpChatCommand {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
