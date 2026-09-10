package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.QuerySuggestionRecomputeInProgressException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySuggestionExceptionHandlerTest {

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void recomputeConflictBecomesALocalized409WithItsMachineCode() {
        // The handler resolves through LocaleContextHolder, so pin it rather than inherit the
        // build machine's default (which is not necessarily plain English).
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        var messages = new StaticMessageSource();
        messages.addMessage("error.query_suggestion_recompute_in_progress", Locale.ENGLISH,
                "A query suggestion recompute for this datasource is already running");
        var datasourceId = UUID.randomUUID();

        var problem = new QuerySuggestionExceptionHandler(messages)
                .handleRecomputeInProgress(
                        new QuerySuggestionRecomputeInProgressException(datasourceId));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        // Resolved through MessageSource, never ex.getMessage() — that would be unlocalized and
        // would leak the internal wording to the client.
        assertThat(problem.getDetail())
                .isEqualTo("A query suggestion recompute for this datasource is already running");
        assertThat(problem.getProperties())
                .containsEntry("error", "QUERY_SUGGESTION_RECOMPUTE_IN_PROGRESS")
                .containsEntry("datasourceId", datasourceId.toString())
                .containsKey("timestamp");
    }

    @Test
    void theExceptionCarriesTheDatasourceItRefersTo() {
        var datasourceId = UUID.randomUUID();

        var ex = new QuerySuggestionRecomputeInProgressException(datasourceId);

        assertThat(ex.datasourceId()).isEqualTo(datasourceId);
        assertThat(ex).hasMessageContaining(datasourceId.toString());
    }
}
