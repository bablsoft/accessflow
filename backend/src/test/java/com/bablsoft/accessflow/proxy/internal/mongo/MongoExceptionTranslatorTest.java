package com.bablsoft.accessflow.proxy.internal.mongo;

import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.mongodb.MongoException;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MongoExceptionTranslatorTest {

    private final MongoExceptionTranslator translator = new MongoExceptionTranslator(messageSource());

    private static StaticMessageSource messageSource() {
        var source = new StaticMessageSource();
        source.addMessage("error.query_execution_timeout", Locale.getDefault(), "timed out");
        source.addMessage("error.query_execution_failed", Locale.getDefault(), "failed");
        return source;
    }

    @Test
    void translatesExecutionTimeout() {
        var result = translator.translate(new MongoExecutionTimeoutException(50, "slow"),
                Duration.ofSeconds(30), Locale.getDefault());
        assertThat(result).isInstanceOf(QueryExecutionTimeoutException.class);
        assertThat(((QueryExecutionTimeoutException) result).timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void translatesServerSelectionTimeout() {
        var result = translator.translate(new MongoTimeoutException("no server"),
                Duration.ofSeconds(5), Locale.getDefault());
        assertThat(result).isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void translatesGenericMongoExceptionAsFailedWithDetail() {
        var result = translator.translate(new MongoException("duplicate key"),
                Duration.ofSeconds(30), Locale.getDefault());
        assertThat(result).isInstanceOf(QueryExecutionFailedException.class);
        assertThat(((QueryExecutionFailedException) result).detail()).isEqualTo("duplicate key");
    }
}
