package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.QueryEngineContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchQueryEngineTest {

    private final ElasticsearchQueryEngine engine = new ElasticsearchQueryEngine();

    @Test
    void reportsTheElasticsearchEngineId() {
        assertThat(engine.engineId()).isEqualTo("elasticsearch");
    }

    @Test
    void throwsWhenUsedBeforeInitialize() {
        assertThatThrownBy(() -> engine.parse("{\"search\":\"logs\"}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void evictAndShutdownAreNullSafeBeforeInitialize() {
        engine.evictDatasource(UUID.randomUUID());
        engine.shutdown();
    }

    @Test
    void parsesAfterInitialize() {
        engine.initialize(new QueryEngineContext(TestMessages.keyEcho(), c -> c, Map.of(),
                Clock.systemDefaultZone().withZone(ZoneOffset.UTC)));
        assertThat(engine.parse("{\"search\":\"logs\"}").referencedTables()).containsExactly("logs");
    }
}
