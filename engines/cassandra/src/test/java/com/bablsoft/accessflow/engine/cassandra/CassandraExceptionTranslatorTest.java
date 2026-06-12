package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CassandraExceptionTranslatorTest {

    private final CassandraExceptionTranslator translator =
            new CassandraExceptionTranslator(TestMessages.keyEcho());

    @Test
    void mapsDriverTimeoutToTimeoutException() {
        var translated = translator.translate(
                new DriverTimeoutException("query timed out"), Duration.ofSeconds(30));
        assertThat(translated).isInstanceOf(QueryExecutionTimeoutException.class);
        assertThat(((QueryExecutionTimeoutException) translated).timeout())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void mapsOtherDriverExceptionsToFailedWithDetail() {
        var ex = mock(DriverException.class);
        when(ex.getMessage()).thenReturn("boom");
        var translated = translator.translate(ex, Duration.ofSeconds(30));
        assertThat(translated).isInstanceOf(QueryExecutionFailedException.class);
        assertThat(((QueryExecutionFailedException) translated).detail()).isEqualTo("boom");
    }
}
