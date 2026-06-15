package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.Neo4jException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Neo4jExceptionTranslatorTest {

    private final Neo4jExceptionTranslator translator =
            new Neo4jExceptionTranslator(TestMessages.keyEcho());

    @Test
    void mapsTransactionTimedOutCodeToTimeoutException() {
        var ex = mock(Neo4jException.class);
        when(ex.code()).thenReturn("Neo.ClientError.Transaction.TransactionTimedOut");
        var translated = translator.translate(ex, Duration.ofSeconds(30));
        assertThat(translated).isInstanceOf(QueryExecutionTimeoutException.class);
        assertThat(((QueryExecutionTimeoutException) translated).timeout())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void mapsTimedOutMessageToTimeoutException() {
        var ex = mock(Neo4jException.class);
        when(ex.code()).thenReturn("Neo.DatabaseError.General.UnknownError");
        when(ex.getMessage()).thenReturn("The transaction has timed out");
        var translated = translator.translate(ex, Duration.ofSeconds(15));
        assertThat(translated).isInstanceOf(QueryExecutionTimeoutException.class);
    }

    @Test
    void mapsOtherExceptionsToFailedWithDetail() {
        var ex = mock(Neo4jException.class);
        when(ex.code()).thenReturn("Neo.ClientError.Statement.SyntaxError");
        when(ex.getMessage()).thenReturn("Invalid syntax");
        var translated = translator.translate(ex, Duration.ofSeconds(30));
        assertThat(translated).isInstanceOf(QueryExecutionFailedException.class);
        assertThat(((QueryExecutionFailedException) translated).detail()).isEqualTo("Invalid syntax");
    }
}
