package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryAffectedRowsResult;
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
 * The {@link QueryEngine} SPI implementation for Couchbase (SQL++ / N1QL) — the entry point the
 * host discovers via {@link java.util.ServiceLoader} from the shaded plugin JAR (see
 * {@code META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine}).
 * {@link #initialize(QueryEngineContext)} performs the wiring Spring DI would otherwise do:
 * settings are parsed from the context's config map, and the parser / row-security applier /
 * result mapper / exception translator / cluster manager are composed into the executor and the
 * admin probes.
 */
public final class CouchbaseQueryEngine implements QueryEngine {

    static final String ENGINE_ID = "couchbase";

    private volatile CouchbaseQueryParser parser;
    private volatile CouchbaseQueryExecutor executor;
    private volatile CouchbaseConnectionProbe connectionProbe;
    private volatile CouchbaseSchemaIntrospector schemaIntrospector;
    private volatile CouchbaseClusterManager clusterManager;
    private volatile CouchbaseRowSecurityApplier rowSecurityApplier;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public CouchbaseQueryEngine() {
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public void initialize(QueryEngineContext context) {
        Objects.requireNonNull(context, "context must not be null");
        var settings = CouchbaseEngineSettings.from(context.config());
        var connectionStringFactory = new CouchbaseConnectionStringFactory();
        var manager = new CouchbaseClusterManager(context.credentials(), connectionStringFactory,
                settings);
        var queryParser = new CouchbaseQueryParser(context.messages());
        var applier = new CouchbaseRowSecurityApplier(context.messages());
        this.clusterManager = manager;
        this.parser = queryParser;
        this.rowSecurityApplier = applier;
        this.executor = new CouchbaseQueryExecutor(manager, queryParser, applier,
                new CouchbaseResultMapper(),
                new CouchbaseExceptionTranslator(context.messages()), settings.scanConsistency(),
                context.clock());
        this.connectionProbe = new CouchbaseConnectionProbe(context.credentials(),
                connectionStringFactory);
        this.schemaIntrospector = new CouchbaseSchemaIntrospector(context.credentials(),
                connectionStringFactory);
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
        CouchbaseStatement statement;
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
        var manager = clusterManager;
        if (manager != null) {
            manager.evict(datasourceId);
        }
    }

    @Override
    public void shutdown() {
        var manager = clusterManager;
        if (manager != null) {
            manager.closeAll();
        }
    }

    private static <T> T initialized(T component) {
        if (component == null) {
            throw new IllegalStateException("CouchbaseQueryEngine used before initialize()");
        }
        return component;
    }
}
