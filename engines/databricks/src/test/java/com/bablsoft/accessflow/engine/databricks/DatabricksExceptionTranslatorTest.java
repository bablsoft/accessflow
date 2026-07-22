package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabricksExceptionTranslatorTest {

    private final DatabricksExceptionTranslator translator = new DatabricksExceptionTranslator(
            TestMessages.of(Map.of(
                    "error.query_execution_timeout", "timed out after {0}s",
                    "error.query_execution_failed", "execution failed")));

    @Test
    void deadlineExpiryBecomesTimeoutException() {
        var ex = new DatabricksApiException("Statement execution deadline exceeded", null, 0, true);
        var translated = translator.translate(ex, Duration.ofSeconds(30));
        assertThat(translated).isInstanceOf(QueryExecutionTimeoutException.class);
        assertThat(translated.getMessage()).isEqualTo("timed out after 30s");
        assertThat(((QueryExecutionTimeoutException) translated).timeout())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(translated.getCause()).isSameAs(ex);
    }

    @Test
    void apiFailureBecomesFailedExceptionWithVerbatimDetail() {
        var ex = new DatabricksApiException("[TABLE_OR_VIEW_NOT_FOUND] nope", "BAD_REQUEST", 200,
                false);
        var translated = translator.translate(ex, Duration.ofSeconds(30));
        assertThat(translated).isInstanceOf(QueryExecutionFailedException.class);
        var failed = (QueryExecutionFailedException) translated;
        assertThat(failed.getMessage()).isEqualTo("execution failed");
        assertThat(failed.detail()).isEqualTo("[TABLE_OR_VIEW_NOT_FOUND] nope");
        assertThat(failed.vendorCode()).isEqualTo(200);
        assertThat(failed.getCause()).isSameAs(ex);
    }

    @Test
    void httpAuthFailureCarriesStatusCode() {
        var ex = new DatabricksApiException("Invalid access token", "UNAUTHENTICATED", 401, false);
        var failed = (QueryExecutionFailedException) translator.translate(ex, Duration.ofSeconds(5));
        assertThat(failed.detail()).isEqualTo("Invalid access token");
        assertThat(failed.vendorCode()).isEqualTo(401);
    }

    @Test
    void transportFailureWithoutStatusCodeTranslates() {
        var ex = new DatabricksApiException("Databricks API request failed: connection refused",
                new java.io.IOException("connection refused"));
        var failed = (QueryExecutionFailedException) translator.translate(ex, Duration.ofSeconds(5));
        assertThat(failed.detail()).contains("connection refused");
        assertThat(failed.vendorCode()).isZero();
    }
}
