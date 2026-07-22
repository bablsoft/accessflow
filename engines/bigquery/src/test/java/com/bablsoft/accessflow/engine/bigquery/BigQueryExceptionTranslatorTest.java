package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.google.cloud.bigquery.BigQueryError;
import com.google.cloud.bigquery.BigQueryException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BigQueryExceptionTranslatorTest {

    private final BigQueryExceptionTranslator translator = new BigQueryExceptionTranslator(
            TestMessages.of(Map.of(
                    "error.query_execution_failed", "Query execution failed",
                    "error.query_execution_timeout", "Query timed out after {0} seconds")));

    @Test
    void serviceErrorsBecomeFailedWithVerbatimDetailAndCode() {
        var ex = new BigQueryException(404, "Not found: Table proj:ds.missing");
        var translated = translator.translate(ex, Duration.ofSeconds(30));
        assertThat(translated).isInstanceOf(QueryExecutionFailedException.class);
        var failed = (QueryExecutionFailedException) translated;
        assertThat(failed.getMessage()).isEqualTo("Query execution failed");
        assertThat(failed.detail()).isEqualTo("Not found: Table proj:ds.missing");
        assertThat(failed.vendorCode()).isEqualTo(404);
        assertThat(failed.getCause()).isSameAs(ex);
    }

    @Test
    void socketTimeoutsInTheCauseChainBecomeTimeout() {
        var ex = new BigQueryException(new IOException(new SocketTimeoutException("Read timed out")));
        var translated = translator.translate(ex, Duration.ofSeconds(12));
        assertThat(translated).isInstanceOf(QueryExecutionTimeoutException.class);
        var timeout = (QueryExecutionTimeoutException) translated;
        assertThat(timeout.getMessage()).isEqualTo("Query timed out after 12 seconds");
        assertThat(timeout.timeout()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void stoppedJobErrorBecomesTimeout() {
        var error = new BigQueryError("stopped", null,
                "Job execution was cancelled: Job timed out after 10s");
        assertThat(translator.translateJobError(error, Duration.ofSeconds(10)))
                .isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void timeoutWordedJobErrorBecomesTimeout() {
        var error = new BigQueryError("invalid", null, "Operation timed out");
        assertThat(translator.translateJobError(error, Duration.ofSeconds(10)))
                .isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void otherJobErrorsBecomeFailedWithVerbatimDetail() {
        var error = new BigQueryError("invalidQuery", null, "Syntax error at [1:8]");
        var translated = translator.translateJobError(error, Duration.ofSeconds(10));
        assertThat(translated).isInstanceOf(QueryExecutionFailedException.class);
        assertThat(((QueryExecutionFailedException) translated).detail())
                .isEqualTo("Syntax error at [1:8]");
    }

    @Test
    void timeoutExceptionFactoryCarriesTimeoutAndCause() {
        var cause = new SocketTimeoutException("boom");
        var timeout = translator.timeoutException(Duration.ofSeconds(3), cause);
        assertThat(timeout.getMessage()).isEqualTo("Query timed out after 3 seconds");
        assertThat(timeout.timeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(timeout.getCause()).isSameAs(cause);
    }
}
