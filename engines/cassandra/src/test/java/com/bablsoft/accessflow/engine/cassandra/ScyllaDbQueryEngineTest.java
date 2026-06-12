package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScyllaDbQueryEngineTest {

    private static QueryEngineContext context() {
        return new QueryEngineContext(TestMessages.keyEcho(), ciphertext -> ciphertext, Map.of(),
                Clock.systemUTC());
    }

    @Test
    void cassandraAndScyllaExposeDistinctEngineIds() {
        assertThat(new CassandraQueryEngine().engineId()).isEqualTo("cassandra");
        assertThat(new ScyllaDbQueryEngine().engineId()).isEqualTo("scylladb");
    }

    @Test
    void scyllaIsACassandraEngineAndReusesItsParser() {
        var engine = new ScyllaDbQueryEngine();
        assertThat(engine).isInstanceOf(CassandraQueryEngine.class);
        engine.initialize(context());
        var result = engine.parse("SELECT * FROM users WHERE id = 1");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("users");
    }

    @Test
    void usingTheEngineBeforeInitializeFails() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new CassandraQueryEngine().parse("SELECT * FROM users"))
                .isInstanceOf(IllegalStateException.class);
    }
}
