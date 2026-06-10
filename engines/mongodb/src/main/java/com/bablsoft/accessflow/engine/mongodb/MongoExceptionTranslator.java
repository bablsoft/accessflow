package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.mongodb.MongoException;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoServerException;
import com.mongodb.MongoTimeoutException;

import java.time.Duration;

/**
 * Translates native {@link MongoException}s into the proxy's engine-neutral execution exceptions —
 * mirroring the host's {@code SqlExceptionTranslator}. Operation/server-selection timeouts become
 * a {@link QueryExecutionTimeoutException}; everything else becomes a
 * {@link QueryExecutionFailedException} whose {@code detail} carries the verbatim driver message so
 * it surfaces on the query detail page (AF-408). Messages resolve through the host-provided
 * {@link EngineMessages}, which applies the calling thread's locale.
 */
class MongoExceptionTranslator {

    private final EngineMessages messages;

    MongoExceptionTranslator(EngineMessages messages) {
        this.messages = messages;
    }

    QueryExecutionException translate(MongoException ex, Duration timeout) {
        if (ex instanceof MongoExecutionTimeoutException || ex instanceof MongoTimeoutException) {
            return new QueryExecutionTimeoutException(
                    messages.get("error.query_execution_timeout", timeout.toSeconds()),
                    timeout, ex);
        }
        int code = ex instanceof MongoServerException server ? server.getCode() : ex.getCode();
        return new QueryExecutionFailedException(
                messages.get("error.query_execution_failed"),
                ex.getMessage(), null, code, ex);
    }
}
