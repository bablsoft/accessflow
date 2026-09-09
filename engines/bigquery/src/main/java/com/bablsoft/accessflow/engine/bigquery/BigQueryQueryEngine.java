package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryEngine;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineDryRunRequest;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryEngineRowSecurityRequest;
import com.bablsoft.accessflow.core.api.QueryEngineSampleRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.RowSecurityClassification;
import com.bablsoft.accessflow.core.api.SqlParseResult;

import java.util.Objects;
import java.util.UUID;

/**
 * The {@link QueryEngine} SPI implementation for Google BigQuery (GoogleSQL) — the entry point the
 * host discovers via {@link java.util.ServiceLoader} from the shaded plugin JAR (see
 * {@code META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine}).
 * {@link #initialize(QueryEngineContext)} performs the wiring Spring DI would otherwise do:
 * settings are parsed from the context's config map, and the parser / row-security applier /
 * result mapper / exception translator / client manager are composed into the executor and the
 * admin probes. BigQuery's connection is cloud credentials (a service-account key JSON) plus a
 * {@code project[.dataset]} rather than host/port (see {@link BigQueryClientFactory}).
 */
public final class BigQueryQueryEngine implements QueryEngine {

    static final String ENGINE_ID = "bigquery";

    private volatile BigQueryQueryParser parser;
    private volatile BigQueryQueryExecutor executor;
    private volatile BigQueryConnectionProbe connectionProbe;
    private volatile BigQuerySchemaIntrospector schemaIntrospector;
    private volatile BigQueryClientManager clientManager;
    private volatile BigQueryRowSecurityApplier rowSecurityApplier;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public BigQueryQueryEngine() {
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public void initialize(QueryEngineContext context) {
        Objects.requireNonNull(context, "context must not be null");
        var settings = BigQueryEngineSettings.from(context.config());
        var clientFactory = new BigQueryClientFactory(context.credentials(), settings,
                context.messages());
        var manager = new BigQueryClientManager(clientFactory);
        var queryParser = new BigQueryQueryParser(context.messages());
        var applier = new BigQueryRowSecurityApplier(context.messages());
        this.clientManager = manager;
        this.parser = queryParser;
        this.rowSecurityApplier = applier;
        this.executor = new BigQueryQueryExecutor(manager, queryParser, applier,
                new BigQueryResultMapper(),
                new BigQueryExceptionTranslator(context.messages()), context.messages(),
                context.clock());
        this.connectionProbe = new BigQueryConnectionProbe(clientFactory);
        this.schemaIntrospector = new BigQuerySchemaIntrospector(clientFactory);
    }

    @Override
    public SqlParseResult parse(String query) {
        return initialized(parser).parse(query);
    }

    @Override
    public RowSecurityClassification classifyRowSecurity(QueryEngineRowSecurityRequest request) {
        if (request.directives().isEmpty()) {
            return RowSecurityClassification.notApplicable(ENGINE_ID);
        }
        BigQueryStatement statement;
        try {
            statement = initialized(parser).parseStatement(request.query());
        } catch (InvalidSqlException ex) {
            // A stored query that no longer parses cannot be classified — report it as unknown so
            // the simulator counts it as unclassifiable rather than as unaffected.
            return RowSecurityClassification.unknown(ENGINE_ID, ex.getMessage());
        }
        return initialized(rowSecurityApplier).classify(ENGINE_ID, statement, request.directives());
    }

    @Override
    public QueryExecutionResult execute(QueryEngineExecutionRequest request) {
        return initialized(executor).execute(request.request(), request.descriptor(),
                request.effectiveMaxRows(), request.effectiveTimeout());
    }

    @Override
    public QueryDryRunResult dryRun(QueryEngineDryRunRequest request) {
        return initialized(executor).dryRun(request.request(), request.descriptor(),
                request.effectiveTimeout());
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
            throw new IllegalStateException("BigQueryQueryEngine used before initialize()");
        }
        return component;
    }
}
