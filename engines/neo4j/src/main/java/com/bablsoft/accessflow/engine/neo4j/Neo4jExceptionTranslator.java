package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import org.neo4j.driver.exceptions.Neo4jException;

import java.time.Duration;
import java.util.Locale;

/**
 * Translates native {@link Neo4jException}s into the proxy's engine-neutral execution exceptions —
 * mirroring the host's {@code SqlExceptionTranslator}. A server-side transaction timeout (the driver
 * surfaces it with a {@code Neo.ClientError.Transaction.TransactionTimedOut}-style status code)
 * becomes a {@link QueryExecutionTimeoutException}; everything else becomes a
 * {@link QueryExecutionFailedException} whose {@code detail} carries the verbatim driver message so
 * it surfaces on the query detail page. Messages resolve through the host-provided
 * {@link EngineMessages}, which applies the calling thread's locale.
 */
class Neo4jExceptionTranslator {

    private final EngineMessages messages;

    Neo4jExceptionTranslator(EngineMessages messages) {
        this.messages = messages;
    }

    QueryExecutionException translate(Neo4jException ex, Duration timeout) {
        if (isTimeout(ex)) {
            return new QueryExecutionTimeoutException(
                    messages.get("error.query_execution_timeout", timeout.toSeconds()),
                    timeout, ex);
        }
        return new QueryExecutionFailedException(
                messages.get("error.query_execution_failed"),
                ex.getMessage(), code(ex), 0, ex);
    }

    private static boolean isTimeout(Neo4jException ex) {
        var code = code(ex);
        if (code != null && code.toLowerCase(Locale.ROOT).contains("timedout")) {
            return true;
        }
        var message = ex.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("timed out");
    }

    private static String code(Neo4jException ex) {
        try {
            return ex.code();
        } catch (RuntimeException ignored) {
            // Some driver exception subtypes synthesize code() lazily; never let it mask the cause.
            return null;
        }
    }
}
