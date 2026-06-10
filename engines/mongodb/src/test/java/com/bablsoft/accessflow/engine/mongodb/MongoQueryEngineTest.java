package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoQueryEngineTest {

    private static MongoQueryEngine initialized() {
        var engine = new MongoQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        return engine;
    }

    @Test
    void engineIdIsTheConnectorId() {
        assertThat(new MongoQueryEngine().engineId()).isEqualTo("mongodb");
    }

    @Test
    void parseDelegatesToTheMongoParserAfterInitialize() {
        var result = initialized().parse("db.users.find({ age: { $gt: 21 } })");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("users");
    }

    @Test
    void methodsThrowBeforeInitialize() {
        var engine = new MongoQueryEngine();
        assertThatThrownBy(() -> engine.parse("db.users.find({})"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initialize");
    }

    @Test
    void evictAndShutdownAreSafeBeforeInitialize() {
        var engine = new MongoQueryEngine();
        engine.evictDatasource(UUID.randomUUID());
        engine.shutdown();
        // no exception — both are lifecycle-safe no-ops before initialize()
    }

    @Test
    void evictAndShutdownAreIdempotentAfterInitialize() {
        var engine = initialized();
        engine.evictDatasource(UUID.randomUUID());
        engine.shutdown();
        engine.shutdown();
    }
}
