package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.couchbase.client.core.error.CouchbaseException;
import com.couchbase.client.core.error.TimeoutException;

import java.time.Duration;

/**
 * Translates native {@link CouchbaseException}s into the proxy's engine-neutral execution
 * exceptions — mirroring the host's {@code SqlExceptionTranslator}. Operation timeouts
 * (ambiguous or unambiguous) become a {@link QueryExecutionTimeoutException}; everything else
 * becomes a {@link QueryExecutionFailedException} whose {@code detail} carries the verbatim SDK
 * message so it surfaces on the query detail page. Messages resolve through the host-provided
 * {@link EngineMessages}, which applies the calling thread's locale.
 */
class CouchbaseExceptionTranslator {

    private final EngineMessages messages;

    CouchbaseExceptionTranslator(EngineMessages messages) {
        this.messages = messages;
    }

    QueryExecutionException translate(CouchbaseException ex, Duration timeout) {
        if (ex instanceof TimeoutException) {
            return new QueryExecutionTimeoutException(
                    messages.get("error.query_execution_timeout", timeout.toSeconds()),
                    timeout, ex);
        }
        return new QueryExecutionFailedException(
                messages.get("error.query_execution_failed"),
                ex.getMessage(), null, 0, ex);
    }
}
