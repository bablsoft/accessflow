package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.QueryEngine;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryEngineSampleRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.SqlParseResult;

import java.util.Objects;
import java.util.UUID;

/**
 * The {@link QueryEngine} SPI implementation for Snowflake — the entry point the host discovers
 * via {@link java.util.ServiceLoader} from the shaded plugin JAR (see
 * {@code META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine}).
 * {@link #initialize(QueryEngineContext)} performs the wiring Spring DI would otherwise do:
 * settings are parsed from the context's config map, and the parser / row-security applier /
 * result mapper / exception translator / connection factory are composed into the executor and
 * the admin probes. Connections are opened per request and closed immediately (see
 * {@link SnowflakeConnectionFactory}), so {@link #evictDatasource(UUID)} and {@link #shutdown()}
 * have nothing to release.
 */
public final class SnowflakeQueryEngine implements QueryEngine {

    static final String ENGINE_ID = "snowflake";

    private volatile SnowflakeQueryParser parser;
    private volatile SnowflakeQueryExecutor executor;
    private volatile SnowflakeConnectionProbe connectionProbe;
    private volatile SnowflakeSchemaIntrospector schemaIntrospector;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public SnowflakeQueryEngine() {
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public void initialize(QueryEngineContext context) {
        Objects.requireNonNull(context, "context must not be null");
        var settings = SnowflakeEngineSettings.from(context.config());
        var connectionFactory = new SnowflakeConnectionFactory(context.credentials(), settings);
        var queryParser = new SnowflakeQueryParser(context.messages());
        this.parser = queryParser;
        this.executor = new SnowflakeQueryExecutor(connectionFactory, queryParser,
                new SnowflakeRowSecurityApplier(context.messages()), new SnowflakeResultMapper(),
                new SnowflakeExceptionTranslator(context.messages()), context.messages(),
                context.clock());
        this.connectionProbe = new SnowflakeConnectionProbe(connectionFactory, context.messages());
        this.schemaIntrospector = new SnowflakeSchemaIntrospector(connectionFactory,
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
        // No-op: connections are per-request and never cached (see SnowflakeConnectionFactory).
    }

    private static <T> T initialized(T component) {
        if (component == null) {
            throw new IllegalStateException("SnowflakeQueryEngine used before initialize()");
        }
        return component;
    }
}
