package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.QueryEngine;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryEngineSampleRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.SqlParseResult;

import java.net.http.HttpClient;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@link QueryEngine} SPI implementation for Databricks SQL warehouses — the entry point the
 * host discovers via {@link java.util.ServiceLoader} from the shaded plugin JAR (see
 * {@code META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine}).
 * {@link #initialize(QueryEngineContext)} performs the wiring Spring DI would otherwise do:
 * settings are parsed from the context's config map, one shared (thread-safe) JDK
 * {@link HttpClient} is built, and the parser / row-security applier / result mapper / exception
 * translator / statement client are composed into the executor and the admin probes. The engine
 * holds no per-datasource native client state — every call is a stateless HTTPS request to the
 * workspace's Statement Execution API — so {@link #evictDatasource(UUID)} and {@link #shutdown()}
 * are no-ops.
 */
public final class DatabricksQueryEngine implements QueryEngine {

    static final String ENGINE_ID = "databricks";

    private volatile DatabricksQueryParser parser;
    private volatile DatabricksQueryExecutor executor;
    private volatile DatabricksConnectionProbe connectionProbe;
    private volatile DatabricksSchemaIntrospector schemaIntrospector;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public DatabricksQueryEngine() {
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public void initialize(QueryEngineContext context) {
        Objects.requireNonNull(context, "context must not be null");
        var settings = DatabricksEngineSettings.from(context.config());
        var http = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var client = new DatabricksStatementClient(http, settings, context.clock());
        var queryParser = new DatabricksQueryParser(context.messages());
        this.parser = queryParser;
        this.executor = new DatabricksQueryExecutor(client, queryParser,
                new DatabricksRowSecurityApplier(context.messages()), new DatabricksResultMapper(),
                new DatabricksExceptionTranslator(context.messages()), context.credentials(),
                context.messages(), context.clock());
        this.connectionProbe = new DatabricksConnectionProbe(client, context.credentials(),
                context.messages());
        this.schemaIntrospector = new DatabricksSchemaIntrospector(client, context.credentials(),
                context.messages());
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
    public ConnectionTestResult testConnection(DatasourceConnectionDescriptor descriptor) {
        return initialized(connectionProbe).test(descriptor);
    }

    @Override
    public DatabaseSchemaView introspectSchema(DatasourceConnectionDescriptor descriptor) {
        return initialized(schemaIntrospector).introspect(descriptor);
    }

    @Override
    public void evictDatasource(UUID datasourceId) {
        // No per-datasource client state — every call is a stateless API request.
    }

    private static <T> T initialized(T component) {
        if (component == null) {
            throw new IllegalStateException("DatabricksQueryEngine used before initialize()");
        }
        return component;
    }
}
