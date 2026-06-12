package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisExceptionTranslatorTest {

    private final RedisExceptionTranslator translator =
            new RedisExceptionTranslator(TestMessages.keyEcho());
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void socketTimeoutBecomesTimeoutException() {
        var ex = new JedisConnectionException(new SocketTimeoutException("read timed out"));
        assertThat(translator.translate(ex, TIMEOUT))
                .isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void connectionFailureWithoutTimeoutCauseBecomesFailedException() {
        var ex = new JedisConnectionException("connection refused");
        assertThat(translator.translate(ex, TIMEOUT))
                .isInstanceOf(QueryExecutionFailedException.class);
    }

    @Test
    void serverDataErrorBecomesFailedExceptionCarryingDetail() {
        var ex = new JedisDataException("WRONGTYPE Operation against a key holding the wrong kind of value");
        var translated = translator.translate(ex, TIMEOUT);
        assertThat(translated).isInstanceOf(QueryExecutionFailedException.class);
        assertThat(((QueryExecutionFailedException) translated).detail()).contains("WRONGTYPE");
    }
}
