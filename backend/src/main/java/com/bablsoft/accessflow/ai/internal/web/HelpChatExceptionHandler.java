package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.HelpChatQuestionRequiredException;
import com.bablsoft.accessflow.ai.api.HelpChatRateLimitExceededException;
import com.bablsoft.accessflow.ai.api.HelpChatSessionNotFoundException;
import com.bablsoft.accessflow.ai.api.HelpChatUnavailableException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * RFC 9457 responses for the user-facing help chat surface (AF-905).
 *
 * <p>Split out of {@code AiAnalysisExceptionHandler} because these four are the only AI failures a
 * non-admin ever sees, and a help panel has to tell them apart to say anything useful: "your
 * administrator has not switched this on" and "you are asking faster than your organization allows"
 * are different sentences with different fixes. The AI module's other codes stay where they were.
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} for the usual reason — without it the security catch-all
 * advice answers first — and additionally so
 * {@link HelpChatRateLimitExceededException}, which extends the organization-wide
 * {@code AiRateLimitExceededException}, resolves here rather than to the more general handler.
 *
 * <p>Nothing here ever surfaces a stack trace or a provider's raw error text: every {@code detail} is
 * a message-bundle key resolved against the caller's locale.
 */
@RestControllerAdvice(basePackageClasses = HelpChatExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class HelpChatExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(HelpChatExceptionHandler.class);

    /** The unavailability the chat runtime reports when the bound {@code ai_config} is gone. */
    private static final String UNBOUND_KEY = "error.help_chat.unbound";

    private final MessageSource messageSource;

    @ExceptionHandler(HelpChatUnavailableException.class)
    ProblemDetail handleHelpChatUnavailable(HelpChatUnavailableException ex) {
        // 409, not 400: the request was well-formed, the organization's agent simply cannot answer
        // it. The two states are separate codes because they have different owners — one is a switch
        // an admin turned off, the other is a binding that deleting an ai_config silently cleared.
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg(ex.messageKey()));
        pd.setProperty("error", UNBOUND_KEY.equals(ex.messageKey())
                ? "HELP_AGENT_NOT_CONFIGURED" : "HELP_AGENT_DISABLED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(HelpChatRateLimitExceededException.class)
    ProblemDetail handleHelpChatRateLimited(HelpChatRateLimitExceededException ex) {
        log.warn("Help chat per-user rate limit exceeded: {}", ex.getMessage());
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                msg("error.help_chat.rate_limited"));
        pd.setProperty("error", "HELP_CHAT_RATE_LIMITED");
        pd.setProperty("limit", ex.limit());
        pd.setProperty("retryAfterSeconds", ex.retryAfterSeconds());
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(HelpChatQuestionRequiredException.class)
    ProblemDetail handleHelpChatQuestionRequired(HelpChatQuestionRequiredException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                msg("error.help_chat.question_required"));
        pd.setProperty("error", "HELP_CHAT_QUESTION_REQUIRED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(HelpChatSessionNotFoundException.class)
    ProblemDetail handleHelpChatSessionNotFound(HelpChatSessionNotFoundException ex) {
        // Also what a session belonging to another user returns: a transcript is private to the
        // person who had it, and 403 would confirm the id exists.
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.help_chat.session_not_found"));
        pd.setProperty("error", "HELP_CHAT_SESSION_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
