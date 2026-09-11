package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewRulesetException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetConflictException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqlReviewExceptionHandlerTest {

    private final SqlReviewExceptionHandler handler = new SqlReviewExceptionHandler(messageSource());

    private static StaticMessageSource messageSource() {
        var ms = new StaticMessageSource();
        ms.setUseCodeAsDefaultMessage(true);
        ms.addMessage("error.sql_review_ruleset_environment_conflict", Locale.getDefault(), "taken: {0}");
        return ms;
    }

    @Test
    void theAdviceOutranksTheSecurityCatchAll() {
        var order = SqlReviewExceptionHandler.class.getAnnotation(Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void handleNotFoundMapsTo404WithRulesetId() {
        var id = UUID.randomUUID();
        var pd = handler.handleNotFound(new SqlReviewRulesetNotFoundException(id));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getProperties()).containsEntry("error", "SQL_REVIEW_RULESET_NOT_FOUND");
        assertThat(pd.getProperties()).containsEntry("rulesetId", id.toString());
        assertThat(pd.getProperties()).containsKey("timestamp");
        assertThat(pd.getDetail()).isEqualTo("error.sql_review_ruleset_not_found");
    }

    @Test
    void handleEnvironmentConflictMapsTo409WithEnvironment() {
        var pd = handler.handleConflict(new SqlReviewRulesetConflictException(DatasourceEnvironment.STAGING));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "SQL_REVIEW_RULESET_ENVIRONMENT_CONFLICT");
        assertThat(pd.getProperties()).containsEntry("environment", "STAGING");
        assertThat(pd.getDetail()).isEqualTo("taken: STAGING");
    }

    @Test
    void handleDefaultConflictMapsTo409WithoutEnvironment() {
        var pd = handler.handleConflict(new SqlReviewRulesetConflictException(null));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(pd.getProperties()).containsEntry("error", "SQL_REVIEW_RULESET_DEFAULT_CONFLICT");
        assertThat(pd.getProperties()).doesNotContainKey("environment");
        assertThat(pd.getDetail()).isEqualTo("error.sql_review_ruleset_default_conflict");
    }

    @Test
    void handleIllegalMapsTo422WithTheThrowSiteMessage() {
        var pd = handler.handleIllegal(new IllegalSqlReviewRulesetException("unknown rule nope"));
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(pd.getProperties()).containsEntry("error", "SQL_REVIEW_RULESET_INVALID");
        assertThat(pd.getDetail()).isEqualTo("unknown rule nope");
    }
}
