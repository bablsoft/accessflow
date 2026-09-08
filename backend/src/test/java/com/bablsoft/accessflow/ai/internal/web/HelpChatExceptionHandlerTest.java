package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.HelpChatQuestionRequiredException;
import com.bablsoft.accessflow.ai.api.HelpChatRateLimitExceededException;
import com.bablsoft.accessflow.ai.api.HelpChatSessionNotFoundException;
import com.bablsoft.accessflow.ai.api.HelpChatUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HelpChatExceptionHandlerTest {

    private final MessageSource messageSource = mock(MessageSource.class);
    private final HelpChatExceptionHandler handler = new HelpChatExceptionHandler(messageSource);

    @Test
    void mapsASwitchedOffAgentToConflict() {
        when(messageSource.getMessage(eq("error.help_chat.disabled"), any(), any(Locale.class)))
                .thenReturn("The in-app help assistant is turned off for your organization");

        var pd = handler.handleHelpChatUnavailable(
                new HelpChatUnavailableException("error.help_chat.disabled"));

        // 409, not 400: the request was well-formed, the organization's agent cannot answer it.
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "HELP_AGENT_DISABLED");
        assertThat(pd.getDetail())
                .isEqualTo("The in-app help assistant is turned off for your organization");
    }

    /**
     * A distinct code from "disabled": this state is not a switch an admin threw but what deleting
     * the bound {@code ai_config} leaves behind, and the fix is different.
     */
    @Test
    void mapsAnUnboundAgentToItsOwnCode() {
        when(messageSource.getMessage(eq("error.help_chat.unbound"), any(), any(Locale.class)))
                .thenReturn("The help assistant is not bound to an AI configuration");

        var pd = handler.handleHelpChatUnavailable(
                new HelpChatUnavailableException("error.help_chat.unbound"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "HELP_AGENT_NOT_CONFIGURED");
    }

    @Test
    void mapsThePerUserRateLimitToItsOwnCodeWithRetryHints() {
        when(messageSource.getMessage(eq("error.help_chat.rate_limited"), any(), any(Locale.class)))
                .thenReturn("You are asking questions faster than your organization allows");

        var pd = handler.handleHelpChatRateLimited(new HelpChatRateLimitExceededException(6, 60));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(pd.getProperties())
                .containsEntry("error", "HELP_CHAT_RATE_LIMITED")
                .containsEntry("limit", 6)
                .containsEntry("retryAfterSeconds", 60L);
    }

    @Test
    void mapsABlankHelpQuestionToBadRequest() {
        when(messageSource.getMessage(eq("error.help_chat.question_required"), any(), any(Locale.class)))
                .thenReturn("Ask a question to get an answer");

        var pd = handler.handleHelpChatQuestionRequired(new HelpChatQuestionRequiredException());

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("error", "HELP_CHAT_QUESTION_REQUIRED");
    }

    /**
     * 404 and never 403: a transcript is private to the person who had it, and a forbidden would
     * confirm the session id exists.
     */
    @Test
    void mapsHelpChatSessionNotFoundToNotFound() {
        when(messageSource.getMessage(eq("error.help_chat.session_not_found"), any(),
                any(Locale.class))).thenReturn("This help conversation no longer exists");

        var pd = handler.handleHelpChatSessionNotFound(
                new HelpChatSessionNotFoundException(UUID.randomUUID()));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "HELP_CHAT_SESSION_NOT_FOUND");
        assertThat(pd.getDetail()).isEqualTo("This help conversation no longer exists");
    }
}
