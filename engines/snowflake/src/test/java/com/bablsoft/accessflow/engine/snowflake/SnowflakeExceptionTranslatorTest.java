package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SnowflakeExceptionTranslatorTest {

    private final SnowflakeExceptionTranslator translator =
            new SnowflakeExceptionTranslator(TestMessages.keyEcho());

    @Test
    void sqlTimeoutExceptionBecomesTimeout() {
        var translated = translator.translate(new SQLTimeoutException("timed out"),
                Duration.ofSeconds(30));
        assertThat(translated).isInstanceOf(QueryExecutionTimeoutException.class);
        assertThat(((QueryExecutionTimeoutException) translated).timeout())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(translated.getMessage()).contains("error.query_execution_timeout");
    }

    @Test
    void snowflakeCancellationVendorCodeBecomesTimeout() {
        var cancelled = new SQLException("SQL execution canceled", "XX000", 604);
        assertThat(translator.translate(cancelled, Duration.ofSeconds(10)))
                .isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void queryCancelledSqlStateBecomesTimeout() {
        var cancelled = new SQLException("canceled", "57014", 0);
        assertThat(translator.translate(cancelled, Duration.ofSeconds(10)))
                .isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void otherSqlExceptionsBecomeFailureWithVerbatimDetail() {
        var failure = new SQLException("SQL compilation error: Object 'X' does not exist",
                "42S02", 2003);
        var translated = translator.translate(failure, Duration.ofSeconds(10));
        assertThat(translated).isInstanceOf(QueryExecutionFailedException.class);
        var failed = (QueryExecutionFailedException) translated;
        assertThat(failed.getMessage()).contains("error.query_execution_failed");
        assertThat(failed.detail()).isEqualTo("SQL compilation error: Object 'X' does not exist");
        assertThat(failed.sqlState()).isEqualTo("42S02");
        assertThat(failed.vendorCode()).isEqualTo(2003);
        assertThat(failed.getCause()).isSameAs(failure);
    }

    @Test
    void nullSqlStateIsNotACancellation() {
        var failure = new SQLException("boom");
        assertThat(translator.translate(failure, Duration.ofSeconds(10)))
                .isInstanceOf(QueryExecutionFailedException.class);
    }
}
