package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.QueryAffectedRowsResult;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.couchbase.client.java.query.QueryScanConsistency;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit coverage of {@link CouchbaseQueryExecutor#countAffectedRows}: every degrade-to-
 * unsupported branch returns before any cluster access, so the executor is wired with a
 * {@code null} cluster manager — reaching it would NPE and fail the test. The live counting
 * paths (real COUNT(*), row security, non-mutation) are covered by
 * {@link CouchbaseQueryEngineIntegrationTest}.
 */
class CouchbaseQueryExecutorTest {

    private final CouchbaseQueryExecutor executor = new CouchbaseQueryExecutor(
            null, new CouchbaseQueryParser(TestMessages.keyEcho()),
            new CouchbaseRowSecurityApplier(TestMessages.keyEcho()), new CouchbaseResultMapper(),
            new CouchbaseExceptionTranslator(TestMessages.keyEcho()),
            QueryScanConsistency.REQUEST_PLUS, Clock.systemUTC());

    private QueryAffectedRowsResult count(String sql, QueryType type) {
        var request = new QueryExecutionRequest(UUID.randomUUID(), sql, type, null, null,
                List.of(), List.of(), List.of(), false, List.of(sql));
        return executor.countAffectedRows(request, null, Duration.ofSeconds(5));
    }

    @Test
    void nonMutatingQueryTypesAreUnsupported() {
        for (var type : List.of(QueryType.SELECT, QueryType.INSERT, QueryType.DDL)) {
            var result = count("SELECT * FROM users", type);
            assertThat(result.supported()).as(type.name()).isFalse();
            assertThat(result.engineId()).isEqualTo("couchbase");
            assertThat(result.affectedRows()).isNull();
        }
    }

    @Test
    void unparseableSqlIsUnsupportedNotThrown() {
        assertThat(count("GRANT ALL ON users", QueryType.DELETE).supported()).isFalse();
        assertThat(count("DELETE FROM users WHERE (a = 1", QueryType.DELETE)
                .supported()).isFalse();
    }

    @Test
    void mergeClassifiedAsUpdateIsUnsupported() {
        assertThat(count("MERGE INTO users AS t USING staged AS s ON t.id = s.id "
                        + "WHEN MATCHED THEN UPDATE SET t.a = s.a", QueryType.UPDATE)
                .supported()).isFalse();
    }

    @Test
    void failClosedShapesAreUnsupported() {
        for (var sql : List.of(
                "DELETE FROM users USE KEYS ['k1']",
                "DELETE FROM users WHERE uid IN (SELECT RAW id FROM admins)",
                "DELETE FROM users WHERE team = 'eng' LIMIT 5")) {
            assertThat(count(sql, QueryType.DELETE).supported()).as(sql).isFalse();
        }
    }
}
