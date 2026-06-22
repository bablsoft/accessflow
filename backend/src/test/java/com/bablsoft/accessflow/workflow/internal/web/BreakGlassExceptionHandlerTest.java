package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.BreakGlassAlreadyReviewedException;
import com.bablsoft.accessflow.workflow.api.BreakGlassEventNotFoundException;
import com.bablsoft.accessflow.workflow.api.BreakGlassNotPermittedException;
import com.bablsoft.accessflow.workflow.api.SelfAcknowledgeNotAllowedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BreakGlassExceptionHandlerTest {

    private BreakGlassExceptionHandler handler;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        var messages = new StaticMessageSource();
        messages.addMessage("error.break_glass_not_permitted", Locale.ENGLISH, "not permitted");
        messages.addMessage("error.break_glass_event_not_found", Locale.ENGLISH, "not found");
        messages.addMessage("error.break_glass_already_reviewed", Locale.ENGLISH, "already reviewed");
        messages.addMessage("error.break_glass_self_acknowledge", Locale.ENGLISH, "self ack");
        handler = new BreakGlassExceptionHandler(messages);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void notPermittedMapsTo403() {
        var pd = handler.handleNotPermitted(new BreakGlassNotPermittedException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getProperties()).containsEntry("error", "BREAK_GLASS_NOT_PERMITTED");
    }

    @Test
    void notFoundMapsTo404() {
        var pd = handler.handleNotFound(new BreakGlassEventNotFoundException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "BREAK_GLASS_EVENT_NOT_FOUND");
    }

    @Test
    void alreadyReviewedMapsTo409() {
        var pd = handler.handleAlreadyReviewed(
                new BreakGlassAlreadyReviewedException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "BREAK_GLASS_ALREADY_REVIEWED");
    }

    @Test
    void selfAcknowledgeMapsTo403() {
        var pd = handler.handleSelfAcknowledge(
                new SelfAcknowledgeNotAllowedException(UUID.randomUUID()));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getProperties()).containsEntry("error", "SELF_ACKNOWLEDGE_NOT_ALLOWED");
    }
}
