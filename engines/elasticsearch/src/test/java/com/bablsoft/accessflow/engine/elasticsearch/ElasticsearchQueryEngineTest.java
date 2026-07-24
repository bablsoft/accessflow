package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineDryRunRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
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

    @Test
    void countAffectedRowsForAReadEnvelopeIsUnsupportedWithTheElasticsearchEngineId() {
        engine.initialize(new QueryEngineContext(TestMessages.keyEcho(), c -> c, Map.of(),
                Clock.systemDefaultZone().withZone(ZoneOffset.UTC)));
        var descriptor = new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.ELASTICSEARCH, "localhost", 9200, null, "", "", SslMode.DISABLE, 10, 10000,
                false, null, false, null, "elasticsearch", null, null, null, null, true, null, null);
        var request = new QueryExecutionRequest(UUID.randomUUID(), "{\"search\":\"logs\"}",
                QueryType.SELECT, null, null, List.of(), List.of(), List.of(), false, null);
        var result = engine.countAffectedRows(new QueryEngineDryRunRequest(request, descriptor,
                Duration.ofSeconds(30)));
        assertThat(result.supported()).isFalse();
        assertThat(result.engineId()).isEqualTo("elasticsearch");
    }
}
