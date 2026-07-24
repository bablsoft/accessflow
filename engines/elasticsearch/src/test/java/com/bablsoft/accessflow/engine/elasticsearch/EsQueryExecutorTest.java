package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionTimeoutException;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the executor's governed affected-row preflight (AF-624): the by-query mutations
 * are counted via a non-mutating {@code _count} of the row-security-wrapped query; every other
 * operation and every shape/parse failure degrades to {@code unsupported} without throwing.
 * Transport-level tests mock the {@link SearchClientManager} / {@link SearchTransport} seam, the
 * convention of the engine's other Spring-free unit tests.
 */
class EsQueryExecutorTest {

    private static final UUID DS_ID = UUID.randomUUID();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final SearchClientManager clientManager = mock(SearchClientManager.class);
    private final SearchTransport transport = mock(SearchTransport.class);
    private final EsQueryExecutor executor = new EsQueryExecutor(clientManager,
            new EsQueryParser(TestMessages.keyEcho()),
            new EsRowSecurityApplier(TestMessages.keyEcho()), new EsResultMapper(),
            new EsExceptionTranslator(TestMessages.keyEcho()), Clock.systemUTC());
    private final DatasourceConnectionDescriptor descriptor = new DatasourceConnectionDescriptor(
            DS_ID, UUID.randomUUID(), DbType.ELASTICSEARCH, "localhost", 9200, null, "", "",
            SslMode.DISABLE, 10, 10000, false, null, false, null, "elasticsearch", null, null,
            null, null, true, null, null);

    private static QueryExecutionRequest request(String sql, QueryType type,
                                                 List<RowSecurityDirective> rls) {
        return new QueryExecutionRequest(DS_ID, sql, type, null, null, List.of(), List.of(), rls,
                false, null);
    }

    private void givenTransportReturns(String response) {
        when(clientManager.transport(descriptor)).thenReturn(transport);
        when(transport.perform(anyString(), anyString(), anyMap(), any(), anyString()))
                .thenReturn(response);
    }

    @Test
    void countsDeleteByQueryViaNonMutatingCount() {
        givenTransportReturns("{\"count\":2}");
        var result = executor.countAffectedRows("elasticsearch",
                request("{\"delete_by_query\":\"logs\",\"query\":{\"term\":{\"tenant\":\"acme\"}}}",
                        QueryType.DELETE, List.of()),
                descriptor, TIMEOUT);
        assertThat(result.supported()).isTrue();
        assertThat(result.engineId()).isEqualTo("elasticsearch");
        assertThat(result.affectedRows()).isEqualTo(2L);
        assertThat(result.duration()).isNotNull();
        var body = ArgumentCaptor.forClass(String.class);
        verify(transport).perform(eq("POST"), eq("/logs/_count"), eq(Map.of()), body.capture(),
                eq("application/json"));
        assertThat(EsJson.parse(body.getValue()).path("query").path("term").path("tenant").asString())
                .isEqualTo("acme");
    }

    @Test
    void countsUpdateByQueryDefaultingToMatchAllWhenQueryAbsent() {
        givenTransportReturns("{\"count\":3}");
        var result = executor.countAffectedRows("elasticsearch",
                request("{\"update_by_query\":\"logs\"}", QueryType.UPDATE, List.of()),
                descriptor, TIMEOUT);
        assertThat(result.supported()).isTrue();
        assertThat(result.affectedRows()).isEqualTo(3L);
        var body = ArgumentCaptor.forClass(String.class);
        verify(transport).perform(eq("POST"), eq("/logs/_count"), eq(Map.of()), body.capture(),
                eq("application/json"));
        assertThat(EsJson.parse(body.getValue()).path("query").has("match_all")).isTrue();
    }

    @Test
    void wrapsRowSecurityDirectivesInBoolFilterAroundTheUserQuery() {
        givenTransportReturns("{\"count\":1}");
        var directive = new RowSecurityDirective(UUID.randomUUID(), "logs", "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
        var result = executor.countAffectedRows("elasticsearch",
                request("{\"delete_by_query\":\"logs\",\"query\":{\"term\":{\"level\":\"debug\"}}}",
                        QueryType.DELETE, List.of(directive)),
                descriptor, TIMEOUT);
        assertThat(result.supported()).isTrue();
        assertThat(result.affectedRows()).isEqualTo(1L);
        var body = ArgumentCaptor.forClass(String.class);
        verify(transport).perform(eq("POST"), eq("/logs/_count"), eq(Map.of()), body.capture(),
                eq("application/json"));
        var query = EsJson.parse(body.getValue()).path("query");
        assertThat(query.path("bool").path("must").get(0).path("term").path("level").asString())
                .isEqualTo("debug");
        assertThat(query.path("bool").path("filter").get(0).path("term").path("tenant").asString())
                .isEqualTo("acme");
    }

    @Test
    void emptyDirectiveValuesFailClosedToAMatchNothingCount() {
        givenTransportReturns("{\"count\":0}");
        var directive = new RowSecurityDirective(UUID.randomUUID(), "logs", "tenant",
                RowSecurityOperator.IN, List.of());
        var result = executor.countAffectedRows("elasticsearch",
                request("{\"delete_by_query\":\"logs\"}", QueryType.DELETE, List.of(directive)),
                descriptor, TIMEOUT);
        assertThat(result.supported()).isTrue();
        assertThat(result.affectedRows()).isZero();
        var body = ArgumentCaptor.forClass(String.class);
        verify(transport).perform(eq("POST"), eq("/logs/_count"), eq(Map.of()), body.capture(),
                eq("application/json"));
        assertThat(body.getValue()).contains("must_not");
    }

    @Test
    void nonMutatingOperationsAreUnsupportedWithoutTouchingTheTransport() {
        for (var sql : List.of(
                "{\"search\":\"logs\"}",
                "{\"count\":\"logs\"}",
                "{\"index\":\"logs\",\"document\":{\"a\":1}}",
                "{\"bulk\":\"logs\",\"operations\":[{\"document\":{\"a\":1}}]}",
                "{\"create_index\":\"logs\"}",
                "{\"delete_index\":\"logs\"}")) {
            var result = executor.countAffectedRows("elasticsearch",
                    request(sql, QueryType.SELECT, List.of()), descriptor, TIMEOUT);
            assertThat(result.supported()).as(sql).isFalse();
            assertThat(result.engineId()).isEqualTo("elasticsearch");
        }
        verifyNoInteractions(clientManager);
    }

    @Test
    void parseFailureIsUnsupportedNotThrown() {
        var result = executor.countAffectedRows("elasticsearch",
                request("not json at all", QueryType.DELETE, List.of()), descriptor, TIMEOUT);
        assertThat(result.supported()).isFalse();
        assertThat(result.unsupportedReason()).isNotBlank();
        verifyNoInteractions(clientManager);
    }

    @Test
    void stampsTheOpenSearchEngineIdWhenAsked() {
        givenTransportReturns("{\"count\":5}");
        var result = executor.countAffectedRows("opensearch",
                request("{\"delete_by_query\":\"logs\"}", QueryType.DELETE, List.of()),
                descriptor, TIMEOUT);
        assertThat(result.supported()).isTrue();
        assertThat(result.engineId()).isEqualTo("opensearch");
        assertThat(result.affectedRows()).isEqualTo(5L);
    }

    @Test
    void transportFailurePropagatesAsTranslatedExecutionException() {
        when(clientManager.transport(descriptor)).thenReturn(transport);
        when(transport.perform(anyString(), anyString(), anyMap(), any(), anyString()))
                .thenThrow(new SearchTransportException(500, "boom", false, null));
        assertThatThrownBy(() -> executor.countAffectedRows("elasticsearch",
                request("{\"delete_by_query\":\"logs\"}", QueryType.DELETE, List.of()),
                descriptor, TIMEOUT))
                .isInstanceOf(QueryExecutionFailedException.class);
    }

    @Test
    void transportTimeoutPropagatesAsTimeoutException() {
        when(clientManager.transport(descriptor)).thenReturn(transport);
        when(transport.perform(anyString(), anyString(), anyMap(), any(), anyString()))
                .thenThrow(new SearchTransportException(0, null, true, null));
        assertThatThrownBy(() -> executor.countAffectedRows("elasticsearch",
                request("{\"delete_by_query\":\"logs\"}", QueryType.DELETE, List.of()),
                descriptor, TIMEOUT))
                .isInstanceOf(QueryExecutionTimeoutException.class);
    }
}
