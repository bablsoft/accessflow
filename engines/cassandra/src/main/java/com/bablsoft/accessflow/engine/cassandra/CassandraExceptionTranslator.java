package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;

import java.time.Duration;

/**
 * Translates native {@link DriverException}s into the proxy's engine-neutral execution exceptions —
 * mirroring the host's {@code SqlExceptionTranslator}. Client-side and server-side timeouts become
 * a {@link QueryExecutionTimeoutException}; everything else becomes a
 * {@link QueryExecutionFailedException} whose {@code detail} carries the verbatim driver message so
 * it surfaces on the query detail page. Messages resolve through the host-provided
 * {@link EngineMessages}, which applies the calling thread's locale.
 */
class CassandraExceptionTranslator {

    private final EngineMessages messages;

    CassandraExceptionTranslator(EngineMessages messages) {
        this.messages = messages;
    }

    QueryExecutionException translate(DriverException ex, Duration timeout) {
        if (isTimeout(ex)) {
            return new QueryExecutionTimeoutException(
                    messages.get("error.query_execution_timeout", timeout.toSeconds()),
                    timeout, ex);
        }
        return new QueryExecutionFailedException(
                messages.get("error.query_execution_failed"),
                ex.getMessage(), null, 0, ex);
    }

    private static boolean isTimeout(DriverException ex) {
        // Client-side request timeout plus the two server-side coordinator timeouts.
        return ex instanceof DriverTimeoutException
                || ex instanceof ReadTimeoutException
                || ex instanceof WriteTimeoutException;
    }
}
