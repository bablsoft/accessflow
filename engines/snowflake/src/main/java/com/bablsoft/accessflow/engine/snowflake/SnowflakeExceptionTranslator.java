package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;

/**
 * Translates driver {@link SQLException}s into the proxy's engine-neutral execution exceptions —
 * mirroring the host's {@code SqlExceptionTranslator}. A {@link SQLTimeoutException}, Snowflake's
 * statement-cancellation error code 604 ("SQL execution canceled" — what
 * {@code Statement.setQueryTimeout} produces), or SQLSTATE {@code 57014} (query_canceled) become a
 * {@link QueryExecutionTimeoutException}; everything else becomes a
 * {@link QueryExecutionFailedException} whose {@code detail} carries the driver message verbatim
 * (plus SQLSTATE and vendor code) so it surfaces on the query detail page. Messages resolve
 * through the host-provided {@link EngineMessages}, which applies the calling thread's locale.
 */
class SnowflakeExceptionTranslator {

    private static final int QUERY_CANCELLED_VENDOR_CODE = 604;
    private static final String QUERY_CANCELLED_SQL_STATE = "57014";

    private final EngineMessages messages;

    SnowflakeExceptionTranslator(EngineMessages messages) {
        this.messages = messages;
    }

    QueryExecutionException translate(SQLException ex, Duration timeout) {
        if (ex instanceof SQLTimeoutException || isCancellation(ex)) {
            return new QueryExecutionTimeoutException(
                    messages.get("error.query_execution_timeout", timeout.toSeconds()),
                    timeout, ex);
        }
        return new QueryExecutionFailedException(
                messages.get("error.query_execution_failed"),
                ex.getMessage(), ex.getSQLState(), ex.getErrorCode(), ex);
    }

    private static boolean isCancellation(SQLException ex) {
        return ex.getErrorCode() == QUERY_CANCELLED_VENDOR_CODE
                || QUERY_CANCELLED_SQL_STATE.equals(ex.getSQLState());
    }
}
