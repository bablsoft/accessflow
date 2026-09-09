package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryEngine;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryEngineRowSecurityRequest;
import com.bablsoft.accessflow.core.api.QueryEngineSampleRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.RowSecurityClassification;
import com.bablsoft.accessflow.core.api.SqlParseResult;

import java.util.Objects;
import java.util.UUID;

/**
 * The {@link QueryEngine} SPI implementation for Apache Cassandra (CQL) — the entry point the host
 * discovers via {@link java.util.ServiceLoader} from the shaded plugin JAR (see
 * {@code META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine}).
 * {@link #initialize(QueryEngineContext)} performs the wiring Spring DI would otherwise do:
 * settings are parsed from the context's config map, and the parser / row-security applier /
 * result mapper / exception translator / session manager are composed into the executor and the
 * admin probes.
 *
 * <p>Not {@code final}: {@link ScyllaDbQueryEngine} subclasses it to serve the CQL-compatible
 * ScyllaDB connector from the very same JAR, changing only {@link #engineId()}.
 */
public class CassandraQueryEngine implements QueryEngine {

    static final String ENGINE_ID = "cassandra";

    private volatile CqlQueryParser parser;
    private volatile CassandraQueryExecutor executor;
    private volatile CassandraConnectionProbe connectionProbe;
    private volatile CassandraSchemaIntrospector schemaIntrospector;
    private volatile CassandraSessionManager sessionManager;
    private volatile CassandraRowSecurityApplier rowSecurityApplier;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public CassandraQueryEngine() {
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public void initialize(QueryEngineContext context) {
        Objects.requireNonNull(context, "context must not be null");
        var settings = CassandraEngineSettings.from(context.config());
        var sessionFactory = new CassandraSessionFactory(context.credentials(), settings);
        var manager = new CassandraSessionManager(sessionFactory);
        var queryParser = new CqlQueryParser(context.messages());
        var applier = new CassandraRowSecurityApplier(context.messages());
        this.sessionManager = manager;
        this.parser = queryParser;
        this.rowSecurityApplier = applier;
        this.executor = new CassandraQueryExecutor(manager, queryParser, applier,
                new CassandraResultMapper(),
                new CassandraExceptionTranslator(context.messages()), context.clock());
        this.connectionProbe = new CassandraConnectionProbe(sessionFactory);
        this.schemaIntrospector = new CassandraSchemaIntrospector(sessionFactory);
    }

    @Override
    public SqlParseResult parse(String query) {
        return initialized(parser).parse(query);
    }

    @Override
    public RowSecurityClassification classifyRowSecurity(QueryEngineRowSecurityRequest request) {
        // engineId() rather than ENGINE_ID: ScyllaDbQueryEngine subclasses this engine and reports
        // its own id from the same shaded jar.
        if (request.directives().isEmpty()) {
            return RowSecurityClassification.notApplicable(engineId());
        }
        CqlStatement statement;
        try {
            statement = initialized(parser).parseStatement(request.query());
        } catch (InvalidSqlException ex) {
            // A stored query that no longer parses cannot be classified — report it as unknown so
            // the simulator counts it as unclassifiable rather than as unaffected.
            return RowSecurityClassification.unknown(engineId(), ex.getMessage());
        }
        return initialized(rowSecurityApplier).classify(engineId(), statement, request.directives());
    }

    @Override
    public QueryExecutionResult execute(QueryEngineExecutionRequest request) {
        return initialized(executor).execute(request.request(), request.descriptor(),
                request.effectiveMaxRows(), request.effectiveTimeout());
    }

    @Override
    public QueryExecutionResult sampleTable(QueryEngineSampleRequest request) {
        return initialized(executor).sampleTable(request.request(), request.descriptor(),
                request.effectiveMaxRows(), request.effectiveTimeout());
    }

    @Override
    public ConnectionTestResult testConnection(DatasourceConnectionDescriptor descriptor) {
        return initialized(connectionProbe).test(descriptor);
    }

    @Override
    public DatabaseSchemaView introspectSchema(DatasourceConnectionDescriptor descriptor) {
        return initialized(schemaIntrospector).introspect(descriptor);
    }

    @Override
    public void evictDatasource(UUID datasourceId) {
        var manager = sessionManager;
        if (manager != null) {
            manager.evict(datasourceId);
        }
    }

    @Override
    public void shutdown() {
        var manager = sessionManager;
        if (manager != null) {
            manager.closeAll();
        }
    }

    private static <T> T initialized(T component) {
        if (component == null) {
            throw new IllegalStateException("CassandraQueryEngine used before initialize()");
        }
        return component;
    }
}
