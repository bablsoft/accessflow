package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.QueryEngine;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.SqlParseResult;

import java.util.Objects;
import java.util.UUID;

/**
 * The {@link QueryEngine} SPI implementation for Neo4j (Cypher) — the entry point the host discovers
 * via {@link java.util.ServiceLoader} from the shaded plugin JAR (see
 * {@code META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine}).
 * {@link #initialize(QueryEngineContext)} performs the wiring Spring DI would otherwise do: settings
 * are parsed from the context's config map, and the parser / row-security applier / result mapper /
 * exception translator / driver manager are composed into the executor and the admin probes. The
 * connection is a Bolt URI built from host/port + SSL mode (or supplied via {@code jdbc_url_override}
 * for Aura / clustered routing); see {@link Neo4jDriverFactory}.
 */
public final class Neo4jQueryEngine implements QueryEngine {

    static final String ENGINE_ID = "neo4j";

    private volatile CypherQueryParser parser;
    private volatile Neo4jQueryExecutor executor;
    private volatile Neo4jConnectionProbe connectionProbe;
    private volatile Neo4jSchemaIntrospector schemaIntrospector;
    private volatile Neo4jDriverManager driverManager;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public Neo4jQueryEngine() {
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public void initialize(QueryEngineContext context) {
        Objects.requireNonNull(context, "context must not be null");
        var settings = Neo4jEngineSettings.from(context.config());
        var driverFactory = new Neo4jDriverFactory(context.credentials(), settings);
        var manager = new Neo4jDriverManager(driverFactory);
        var queryParser = new CypherQueryParser(context.messages());
        this.driverManager = manager;
        this.parser = queryParser;
        this.executor = new Neo4jQueryExecutor(manager, queryParser,
                new Neo4jRowSecurityApplier(context.messages()), new Neo4jResultMapper(),
                new Neo4jExceptionTranslator(context.messages()), context.clock());
        this.connectionProbe = new Neo4jConnectionProbe(driverFactory);
        this.schemaIntrospector = new Neo4jSchemaIntrospector(driverFactory);
    }

    @Override
    public SqlParseResult parse(String query) {
        return initialized(parser).parse(query);
    }

    @Override
    public QueryExecutionResult execute(QueryEngineExecutionRequest request) {
        return initialized(executor).execute(request.request(), request.descriptor(),
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
        var manager = driverManager;
        if (manager != null) {
            manager.evict(datasourceId);
        }
    }

    @Override
    public void shutdown() {
        var manager = driverManager;
        if (manager != null) {
            manager.closeAll();
        }
    }

    private static <T> T initialized(T component) {
        if (component == null) {
            throw new IllegalStateException("Neo4jQueryEngine used before initialize()");
        }
        return component;
    }
}
