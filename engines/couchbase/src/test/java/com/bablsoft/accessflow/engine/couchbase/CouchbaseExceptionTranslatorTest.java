package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.couchbase.client.core.error.AmbiguousTimeoutException;
import com.couchbase.client.core.error.CouchbaseException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CouchbaseExceptionTranslatorTest {

    private final CouchbaseExceptionTranslator translator =
            new CouchbaseExceptionTranslator(TestMessages.keyEcho());

    @Test
    void timeoutsBecomeQueryExecutionTimeoutException() {
        var translated = translator.translate(
                new AmbiguousTimeoutException("query timed out", null), Duration.ofSeconds(30));
        assertThat(translated).isInstanceOf(QueryExecutionTimeoutException.class);
        assertThat(((QueryExecutionTimeoutException) translated).timeout())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(translated.getMessage()).contains("error.query_execution_timeout");
    }

    @Test
    void otherCouchbaseExceptionsBecomeFailedWithDetail() {
        var translated = translator.translate(
                new CouchbaseException("syntax error near SELECT"), Duration.ofSeconds(30));
        assertThat(translated).isInstanceOf(QueryExecutionFailedException.class);
        var failed = (QueryExecutionFailedException) translated;
        assertThat(failed.getMessage()).contains("error.query_execution_failed");
        assertThat(failed.detail()).contains("syntax error near SELECT");
        assertThat(failed.getCause()).isInstanceOf(CouchbaseException.class);
    }
}
