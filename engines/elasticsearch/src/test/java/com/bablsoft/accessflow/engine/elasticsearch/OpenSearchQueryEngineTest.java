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

    @Test
    void countAffectedRowsInheritsTheSharedLogicButStampsTheOpenSearchEngineId() {
        engine.initialize(new QueryEngineContext(TestMessages.keyEcho(), c -> c, Map.of(),
                Clock.systemDefaultZone().withZone(ZoneOffset.UTC)));
        var descriptor = new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.OPENSEARCH, "localhost", 9200, null, "", "", SslMode.DISABLE, 10, 10000,
                false, null, false, null, "opensearch", null, null, null, null, true, null, null);
        var request = new QueryExecutionRequest(UUID.randomUUID(), "{\"search\":\"events\"}",
                QueryType.SELECT, null, null, List.of(), List.of(), List.of(), false, null);
        var result = engine.countAffectedRows(new QueryEngineDryRunRequest(request, descriptor,
                Duration.ofSeconds(30)));
        assertThat(result.supported()).isFalse();
        assertThat(result.engineId()).isEqualTo("opensearch");
    }
}
