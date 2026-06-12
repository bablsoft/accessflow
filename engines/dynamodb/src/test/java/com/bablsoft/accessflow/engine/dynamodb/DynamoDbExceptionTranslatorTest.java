package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbExceptionTranslatorTest {

    private final DynamoDbExceptionTranslator translator =
            new DynamoDbExceptionTranslator(TestMessages.keyEcho());

    @Test
    void mapsApiCallTimeoutToTimeoutException() {
        var result = translator.translate(ApiCallTimeoutException.create(1000), Duration.ofSeconds(5));
        assertThat(result).isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void mapsApiCallAttemptTimeoutToTimeoutException() {
        var result = translator.translate(ApiCallAttemptTimeoutException.create(1000),
                Duration.ofSeconds(5));
        assertThat(result).isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void mapsServiceExceptionToFailedExceptionWithVerbatimDetail() {
        var result = translator.translate(
                DynamoDbException.builder().message("boom").build(), Duration.ofSeconds(5));
        assertThat(result).isInstanceOf(QueryExecutionFailedException.class);
        assertThat(((QueryExecutionFailedException) result).detail()).contains("boom");
    }

    @Test
    void mapsResourceNotFoundToFailedException() {
        var result = translator.translate(
                ResourceNotFoundException.builder().message("no table").build(), Duration.ofSeconds(5));
        assertThat(result).isInstanceOf(QueryExecutionFailedException.class);
    }
}
