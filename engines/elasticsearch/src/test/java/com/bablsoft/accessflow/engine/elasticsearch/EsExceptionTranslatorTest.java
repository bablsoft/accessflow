package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EsExceptionTranslatorTest {

    private final EsExceptionTranslator translator = new EsExceptionTranslator(TestMessages.keyEcho());

    @Test
    void translatesTransportTimeoutToTimeoutException() {
        var ex = translator.translate(
                new SearchTransportException(0, null, true, null), Duration.ofSeconds(7));
        assertThat(ex).isInstanceOf(QueryExecutionTimeoutException.class);
        assertThat(((QueryExecutionTimeoutException) ex).timeout()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void translatesHttpErrorToFailedExceptionCarryingTheResponseBody() {
        var ex = translator.translate(
                new SearchTransportException(400, "{\"error\":\"bad\"}", false, null),
                Duration.ofSeconds(7));
        assertThat(ex).isInstanceOf(QueryExecutionFailedException.class);
        assertThat(((QueryExecutionFailedException) ex).detail()).isEqualTo("{\"error\":\"bad\"}");
        assertThat(((QueryExecutionFailedException) ex).vendorCode()).isEqualTo(400);
    }

    @Test
    void exposesTimedOutAndBulkFailedHelpers() {
        assertThat(translator.timedOut(Duration.ofSeconds(7)))
                .isInstanceOf(QueryExecutionTimeoutException.class);
        var bulk = translator.bulkFailed("{\"reason\":\"x\"}");
        assertThat(bulk.detail()).isEqualTo("{\"reason\":\"x\"}");
    }
}
