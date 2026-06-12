package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.QueryEngineContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSearchQueryEngineTest {

    private final OpenSearchQueryEngine engine = new OpenSearchQueryEngine();

    @Test
    void reportsTheOpenSearchEngineId() {
        assertThat(engine.engineId()).isEqualTo("opensearch");
    }

    @Test
    void sharesTheElasticsearchParseLogic() {
        engine.initialize(new QueryEngineContext(TestMessages.keyEcho(), c -> c, Map.of(),
                Clock.systemDefaultZone().withZone(ZoneOffset.UTC)));
        assertThat(engine.parse("{\"count\":\"events\"}").referencedTables())
                .containsExactly("events");
    }
}
