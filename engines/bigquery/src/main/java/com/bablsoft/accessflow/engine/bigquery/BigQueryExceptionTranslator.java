package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.google.cloud.bigquery.BigQueryError;
import com.google.cloud.bigquery.BigQueryException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Locale;

/**
 * Translates native {@link BigQueryException}s and completed-job {@link BigQueryError}s into the
 * proxy's engine-neutral execution exceptions — mirroring the host's {@code SqlExceptionTranslator}.
 * Socket read timeouts and job errors whose reason/message indicate a timeout or cancellation
 * (the {@code jobTimeoutMs} kill shows up as reason {@code stopped}) become a
 * {@link QueryExecutionTimeoutException}; everything else (syntax errors, missing tables, quota,
 * permission denials) becomes a {@link QueryExecutionFailedException} whose {@code detail} carries
 * the verbatim BigQuery message so it surfaces on the query detail page. Messages resolve through
 * the host-provided {@link EngineMessages}, which applies the calling thread's locale.
 */
class BigQueryExceptionTranslator {

    private final EngineMessages messages;

    BigQueryExceptionTranslator(EngineMessages messages) {
        this.messages = messages;
    }

    QueryExecutionException translate(BigQueryException ex, Duration timeout) {
        if (isSocketTimeout(ex)) {
            return timeoutException(timeout, ex);
        }
        return new QueryExecutionFailedException(
                messages.get("error.query_execution_failed"),
                ex.getMessage(), null, ex.getCode(), ex);
    }

    /** Translates the terminal error of a DONE job (there is no exception to wrap). */
    QueryExecutionException translateJobError(BigQueryError error, Duration timeout) {
        if (isTimeoutError(error)) {
            return timeoutException(timeout, null);
        }
        return new QueryExecutionFailedException(
                messages.get("error.query_execution_failed"),
                error.getMessage(), null, 0, null);
    }

    QueryExecutionTimeoutException timeoutException(Duration timeout, Throwable cause) {
        return new QueryExecutionTimeoutException(
                messages.get("error.query_execution_timeout", timeout.toSeconds()),
                timeout, cause);
    }

    private static boolean isSocketTimeout(BigQueryException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTimeoutError(BigQueryError error) {
        var reason = error.getReason() == null ? "" : error.getReason().toLowerCase(Locale.ROOT);
        var message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return reason.equals("stopped") || reason.equals("timeout")
                || message.contains("timed out") || message.contains("timeout");
    }
}
