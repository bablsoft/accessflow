package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;

import java.time.Duration;

/**
 * Translates internal {@link DatabricksApiException}s into the proxy's engine-neutral execution
 * exceptions — mirroring the host's {@code SqlExceptionTranslator}. A deadline expiry (the engine
 * cancelled the statement best-effort) becomes a {@link QueryExecutionTimeoutException};
 * everything else (a terminal {@code FAILED} state, 401/403, 429/5xx, transport failures) becomes
 * a {@link QueryExecutionFailedException} whose {@code detail} carries the verbatim API message so
 * it surfaces on the query detail page. Messages resolve through the host-provided
 * {@link EngineMessages}, which applies the calling thread's locale.
 */
class DatabricksExceptionTranslator {

    private final EngineMessages messages;

    DatabricksExceptionTranslator(EngineMessages messages) {
        this.messages = messages;
    }

    QueryExecutionException translate(DatabricksApiException ex, Duration timeout) {
        if (ex.timedOut()) {
            return new QueryExecutionTimeoutException(
                    messages.get("error.query_execution_timeout", timeout.toSeconds()),
                    timeout, ex);
        }
        return new QueryExecutionFailedException(
                messages.get("error.query_execution_failed"),
                ex.getMessage(), null, ex.statusCode(), ex);
    }
}
