package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;

import java.time.Duration;

/**
 * Translates transport / HTTP failures and the two soft-failure shapes the search engines return on
 * a 200 response — a search {@code timed_out:true} and a bulk {@code errors:true} — into the proxy's
 * engine-neutral execution exceptions, mirroring the host's {@code SqlExceptionTranslator}. Timeouts
 * become a {@link QueryExecutionTimeoutException}; everything else a {@link QueryExecutionFailedException}
 * whose {@code detail} carries the cluster's response body so it surfaces on the query detail page.
 * Messages resolve through the host-provided {@link EngineMessages} for the calling thread's locale.
 */
class EsExceptionTranslator {

    private final EngineMessages messages;

    EsExceptionTranslator(EngineMessages messages) {
        this.messages = messages;
    }

    QueryExecutionException translate(SearchTransportException ex, Duration timeout) {
        if (ex.timeout()) {
            return timedOut(timeout);
        }
        return new QueryExecutionFailedException(
                messages.get("error.query_execution_failed"),
                ex.responseBody() != null ? ex.responseBody() : ex.getMessage(),
                null, ex.statusCode(), ex);
    }

    /** A {@code timed_out:true} search / by-query response (HTTP 200 with partial results). */
    QueryExecutionTimeoutException timedOut(Duration timeout) {
        return new QueryExecutionTimeoutException(
                messages.get("error.query_execution_timeout", timeout.toSeconds()), timeout, null);
    }

    /** A bulk response with {@code errors:true} — fail the whole write rather than report partial. */
    QueryExecutionFailedException bulkFailed(String detail) {
        return new QueryExecutionFailedException(
                messages.get("error.elasticsearch.bulk_failed"), detail, null, 0, null);
    }
}
