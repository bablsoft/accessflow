package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;

import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Translates native {@link JedisException}s into the proxy's engine-neutral execution exceptions —
 * mirroring the host's {@code SqlExceptionTranslator}. A socket-read timeout (a
 * {@link JedisConnectionException} caused by a {@link SocketTimeoutException}) becomes a
 * {@link QueryExecutionTimeoutException}; everything else becomes a
 * {@link QueryExecutionFailedException} whose {@code detail} carries the verbatim driver message so
 * it surfaces on the query detail page. Messages resolve through the host-provided
 * {@link EngineMessages}, which applies the calling thread's locale.
 */
class RedisExceptionTranslator {

    private final EngineMessages messages;

    RedisExceptionTranslator(EngineMessages messages) {
        this.messages = messages;
    }

    QueryExecutionException translate(JedisException ex, Duration timeout) {
        if (isTimeout(ex)) {
            return new QueryExecutionTimeoutException(
                    messages.get("error.query_execution_timeout", timeout.toSeconds()),
                    timeout, ex);
        }
        return new QueryExecutionFailedException(
                messages.get("error.query_execution_failed"),
                ex.getMessage(), null, 0, ex);
    }

    private static boolean isTimeout(JedisException ex) {
        return ex instanceof JedisConnectionException
                && ex.getCause() instanceof SocketTimeoutException;
    }
}
