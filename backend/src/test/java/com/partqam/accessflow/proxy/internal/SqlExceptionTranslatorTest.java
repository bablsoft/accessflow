package com.partqam.accessflow.proxy.internal;

import com.partqam.accessflow.proxy.api.QueryExecutionFailedException;
import com.partqam.accessflow.proxy.api.QueryExecutionTimeoutException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SqlExceptionTranslatorTest {

    private final SqlExceptionTranslator translator = new SqlExceptionTranslator();
    private final Duration timeout = Duration.ofSeconds(30);

    @Test
    void sqlTimeoutExceptionMapsToTimeout() {
        var ex = new SQLTimeoutException("query timed out", "57014", 0);

        var translated = translator.translate(ex, timeout);

        assertThat(translated).isInstanceOf(QueryExecutionTimeoutException.class);
        assertThat(((QueryExecutionTimeoutException) translated).timeout()).isEqualTo(timeout);
    }

    @Test
    void postgresCancelledStateMapsToTimeout() {
        var ex = new SQLException("query cancelled", "57014", 0);

        var translated = translator.translate(ex, timeout);

        assertThat(translated).isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void mysqlTimeoutStateMapsToTimeout() {
        var ex = new SQLException("operation cancelled", "HY008", 0);

        var translated = translator.translate(ex, timeout);

        assertThat(translated).isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void mysqlConnectionKilledStateMapsToTimeout() {
        var ex = new SQLException("connection killed", "70100", 1317);

        var translated = translator.translate(ex, timeout);

        assertThat(translated).isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void otherSqlStatesMapToFailed() {
        var ex = new SQLException("relation does not exist", "42P01", 7);

        var translated = translator.translate(ex, timeout);

        assertThat(translated).isInstanceOf(QueryExecutionFailedException.class);
        var failed = (QueryExecutionFailedException) translated;
        assertThat(failed.sqlState()).isEqualTo("42P01");
        assertThat(failed.vendorCode()).isEqualTo(7);
        assertThat(failed.getCause()).isSameAs(ex);
    }

    @Test
    void nullSqlStateMapsToFailed() {
        var ex = new SQLException("oops");

        var translated = translator.translate(ex, timeout);

        assertThat(translated).isInstanceOf(QueryExecutionFailedException.class);
        assertThat(((QueryExecutionFailedException) translated).sqlState()).isNull();
    }
}
