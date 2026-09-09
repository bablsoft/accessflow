package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineDryRunRequest;
import com.bablsoft.accessflow.core.api.QueryEngineRowSecurityRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
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

    private static DatasourceConnectionDescriptor descriptor() {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.MONGODB, "127.0.0.1", 27017, "db", null, "", SslMode.DISABLE, 10, 1000,
                true, null, false, null, null, null, null, null, null, true);
    }

    private static QueryEngineDryRunRequest dryRunRequest(String query, QueryType type) {
        var request = new QueryExecutionRequest(UUID.randomUUID(), query, type, null, null,
                List.of(), List.of(), List.of(), false, List.of(query));
        return new QueryEngineDryRunRequest(request, descriptor(), Duration.ofSeconds(5));
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
    void countAffectedRowsIsUnsupportedForNonWriteQueryTypes() {
        var result = initialized().countAffectedRows(
                dryRunRequest("db.users.find({})", QueryType.SELECT));
        assertThat(result.supported()).isFalse();
        assertThat(result.engineId()).isEqualTo("mongodb");
        assertThat(result.affectedRows()).isNull();
    }

    @Test
    void countAffectedRowsIsUnsupportedForInsertQueryTypes() {
        var result = initialized().countAffectedRows(
                dryRunRequest("db.users.insertOne({ a: 1 })", QueryType.INSERT));
        assertThat(result.supported()).isFalse();
    }

    @Test
    void countAffectedRowsIsUnsupportedForUnparseableQueries() {
        var result = initialized().countAffectedRows(
                dryRunRequest("this is not a mongo query", QueryType.DELETE));
        assertThat(result.supported()).isFalse();
        assertThat(result.engineId()).isEqualTo("mongodb");
        assertThat(result.unsupportedReason()).isNotBlank();
    }

    @Test
    void countAffectedRowsIsUnsupportedWhenParsedOperationIsNotUpdateOrDelete() {
        // Declared DELETE but the statement parses as a read — fail closed, no count.
        var result = initialized().countAffectedRows(
                dryRunRequest("db.users.find({})", QueryType.DELETE));
        assertThat(result.supported()).isFalse();
    }

    @Test
    void countAffectedRowsThrowsBeforeInitialize() {
        var engine = new MongoQueryEngine();
        assertThatThrownBy(() -> engine.countAffectedRows(
                dryRunRequest("db.users.deleteMany({})", QueryType.DELETE)))
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

    // ---- offline row-security classification (AF-630) -------------------------------------------

    private static QueryEngineRowSecurityRequest classifyRequest(String query,
                                                                 RowSecurityDirective... directives) {
        return new QueryEngineRowSecurityRequest(UUID.randomUUID(), query, List.of(directives));
    }

    private static RowSecurityDirective tenantEquals(String collection) {
        return new RowSecurityDirective(UUID.randomUUID(), collection, "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
    }

    @Test
    void classifyRowSecurityShortCircuitsWhenThereAreNoDirectives() {
        var result = initialized().classifyRowSecurity(classifyRequest("db.users.find({})"));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo("mongodb");
    }

    @Test
    void classifyRowSecurityDelegatesToTheApplier() {
        var directive = tenantEquals("users");
        var result = initialized()
                .classifyRowSecurity(classifyRequest("db.users.find({})", directive));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds()).containsExactly(directive.policyId());
    }

    @Test
    void classifyRowSecurityReportsFailClosedForAPoliciedInsert() {
        var result = initialized().classifyRowSecurity(
                classifyRequest("db.users.insertOne({ name: \"ada\" })", tenantEquals("users")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityReportsUnknownRatherThanSafeForAnUnparseableQuery() {
        var result = initialized()
                .classifyRowSecurity(classifyRequest("not a mongo command", tenantEquals("users")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void classifyRowSecurityFailsBeforeInitialize() {
        assertThatThrownBy(() -> new MongoQueryEngine()
                .classifyRowSecurity(classifyRequest("db.users.find({})", tenantEquals("users"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
