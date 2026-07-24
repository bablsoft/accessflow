package com.bablsoft.accessflow.engine.mongodb;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.QueryAffectedRowsResult;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryEngine;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineDryRunRequest;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryEngineSampleRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.SqlParseResult;

import java.util.Objects;
import java.util.UUID;

/**
 * The {@link QueryEngine} SPI implementation for MongoDB — the entry point the host discovers via
 * {@link java.util.ServiceLoader} from the shaded plugin JAR (see
 * {@code META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine}).
 * {@link #initialize(QueryEngineContext)} performs the wiring Spring DI used to do when this engine
 * was bundled in the host: settings are parsed from the context's config map, and the parser /
 * row-security applier / result mapper / exception translator / client manager are composed into
 * the executor and the admin probes.
 */
public final class MongoQueryEngine implements QueryEngine {

    static final String ENGINE_ID = "mongodb";

    private volatile MongoQueryParser parser;
    private volatile MongoQueryExecutor executor;
    private volatile MongoConnectionProbe connectionProbe;
    private volatile MongoSchemaIntrospector schemaIntrospector;
    private volatile MongoClientManager clientManager;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public MongoQueryEngine() {
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public void initialize(QueryEngineContext context) {
        Objects.requireNonNull(context, "context must not be null");
        var settings = MongoEngineSettings.from(context.config());
        var connectionStringFactory = new MongoConnectionStringFactory();
        var manager = new MongoClientManager(context.credentials(), connectionStringFactory,
                settings);
        var queryParser = new MongoQueryParser(context.messages());
        this.clientManager = manager;
        this.parser = queryParser;
        this.executor = new MongoQueryExecutor(manager, queryParser,
                new MongoRowSecurityApplier(context.messages()), new MongoResultMapper(),
                new MongoExceptionTranslator(context.messages()), context.clock());
        this.connectionProbe = new MongoConnectionProbe(context.credentials(),
                connectionStringFactory);
        this.schemaIntrospector = new MongoSchemaIntrospector(context.credentials(),
                connectionStringFactory);
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
    public QueryExecutionResult sampleTable(QueryEngineSampleRequest request) {
        return initialized(executor).sampleTable(request.request(), request.descriptor(),
                request.effectiveMaxRows(), request.effectiveTimeout());
    }

    @Override
    public QueryDryRunResult dryRun(QueryEngineDryRunRequest request) {
        return initialized(executor).dryRun(request.request(), request.descriptor(),
                request.effectiveTimeout());
    }

    @Override
    public QueryAffectedRowsResult countAffectedRows(QueryEngineDryRunRequest request) {
        return initialized(executor).countAffectedRows(request.request(), request.descriptor(),
                request.effectiveTimeout());
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
        var manager = clientManager;
        if (manager != null) {
            manager.evict(datasourceId);
        }
    }

    @Override
    public void shutdown() {
        var manager = clientManager;
        if (manager != null) {
            manager.closeAll();
        }
    }

    private static <T> T initialized(T component) {
        if (component == null) {
            throw new IllegalStateException("MongoQueryEngine used before initialize()");
        }
        return component;
    }
}
