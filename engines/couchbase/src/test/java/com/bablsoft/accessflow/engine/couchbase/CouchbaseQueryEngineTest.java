package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.QueryEngineContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouchbaseQueryEngineTest {

    @Test
    void engineIdMatchesConnectorIdAndFolderName() {
        assertThat(new CouchbaseQueryEngine().engineId()).isEqualTo("couchbase");
    }

    @Test
    void usingTheEngineBeforeInitializeFails() {
        var engine = new CouchbaseQueryEngine();
        assertThatThrownBy(() -> engine.parse("SELECT 1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before initialize");
        // Eviction and shutdown before initialize are safe no-ops.
        engine.evictDatasource(UUID.randomUUID());
        engine.shutdown();
    }

    @Test
    void initializeWiresTheParser() {
        var engine = new CouchbaseQueryEngine();
        engine.initialize(new QueryEngineContext(
                TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(), Clock.systemUTC()));
        assertThat(engine.parse("SELECT 1").statements()).containsExactly("SELECT 1");
    }
}
