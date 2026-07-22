package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeQueryEngineTest {

    private static QueryEngineContext context() {
        return new QueryEngineContext(TestMessages.keyEcho(), ciphertext -> ciphertext,
                Map.of(), Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void engineIdMatchesConnectorId() {
        assertThat(new SnowflakeQueryEngine().engineId()).isEqualTo("snowflake");
    }

    @Test
    void useBeforeInitializeFailsFast() {
        var engine = new SnowflakeQueryEngine();
        assertThatThrownBy(() -> engine.parse("SELECT 1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before initialize");
    }

    @Test
    void initializeWiresTheParser() {
        var engine = new SnowflakeQueryEngine();
        engine.initialize(context());
        var result = engine.parse("SELECT * FROM orders WHERE id = 1");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("orders");
    }

    @Test
    void initializeRejectsNullContext() {
        var engine = new SnowflakeQueryEngine();
        assertThatThrownBy(() -> engine.initialize(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void evictAndShutdownAreNoOpsEvenBeforeInitialize() {
        var engine = new SnowflakeQueryEngine();
        assertThatCode(() -> {
            engine.evictDatasource(UUID.randomUUID());
            engine.shutdown();
        }).doesNotThrowAnyException();
    }
}
