package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;

import java.time.Duration;

/**
 * Translates native AWS SDK {@link SdkException}s into the proxy's engine-neutral execution
 * exceptions — mirroring the host's {@code SqlExceptionTranslator}. Client-side API-call timeouts
 * become a {@link QueryExecutionTimeoutException}; everything else (service errors such as
 * {@code ResourceNotFoundException}, {@code ValidationException}, throttling) becomes a
 * {@link QueryExecutionFailedException} whose {@code detail} carries the verbatim AWS message so it
 * surfaces on the query detail page. Messages resolve through the host-provided
 * {@link EngineMessages}, which applies the calling thread's locale.
 */
class DynamoDbExceptionTranslator {

    private final EngineMessages messages;

    DynamoDbExceptionTranslator(EngineMessages messages) {
        this.messages = messages;
    }

    QueryExecutionException translate(SdkException ex, Duration timeout) {
        if (ex instanceof ApiCallTimeoutException || ex instanceof ApiCallAttemptTimeoutException) {
            return new QueryExecutionTimeoutException(
                    messages.get("error.query_execution_timeout", timeout.toSeconds()),
                    timeout, ex);
        }
        return new QueryExecutionFailedException(
                messages.get("error.query_execution_failed"),
                ex.getMessage(), null, 0, ex);
    }
}
