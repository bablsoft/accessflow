package com.bablsoft.accessflow.proxy.internal.mongo;

import com.bablsoft.accessflow.core.api.QueryExecutionException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.mongodb.MongoException;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoServerException;
import com.mongodb.MongoTimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Translates native {@link MongoException}s into the proxy's engine-neutral execution exceptions —
 * mirroring {@code SqlExceptionTranslator}. Operation/server-selection timeouts become a
 * {@link QueryExecutionTimeoutException}; everything else becomes a
 * {@link QueryExecutionFailedException} whose {@code detail} carries the verbatim driver message so
 * it surfaces on the query detail page (AF-408).
 */
@Component
@RequiredArgsConstructor
class MongoExceptionTranslator {

    private final MessageSource messageSource;

    QueryExecutionException translate(MongoException ex, Duration timeout, Locale locale) {
        if (ex instanceof MongoExecutionTimeoutException || ex instanceof MongoTimeoutException) {
            return new QueryExecutionTimeoutException(
                    messageSource.getMessage("error.query_execution_timeout",
                            new Object[]{timeout.toSeconds()}, locale),
                    timeout, ex);
        }
        int code = ex instanceof MongoServerException server ? server.getCode() : ex.getCode();
        return new QueryExecutionFailedException(
                messageSource.getMessage("error.query_execution_failed", null, locale),
                ex.getMessage(), null, code, ex);
    }
}
