package com.bablsoft.accessflow.engine.redis;

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
 * The {@link QueryEngine} SPI implementation for Redis — the entry point the host discovers via
 * {@link java.util.ServiceLoader} from the shaded plugin JAR (see
 * {@code META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine}).
 * {@link #initialize(QueryEngineContext)} performs the wiring Spring DI would otherwise do: settings
 * are parsed from the context's config map, and the parser / executor / connection probe / schema
 * introspector are composed around the per-datasource Jedis client manager.
 */
public final class RedisQueryEngine implements QueryEngine {

    static final String ENGINE_ID = "redis";

    private volatile RedisCommandParser parser;
    private volatile RedisQueryExecutor executor;
    private volatile RedisConnectionProbe connectionProbe;
    private volatile RedisSchemaIntrospector schemaIntrospector;
    private volatile RedisClientManager clientManager;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public RedisQueryEngine() {
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public void initialize(QueryEngineContext context) {
        Objects.requireNonNull(context, "context must not be null");
        var settings = RedisEngineSettings.from(context.config());
        var manager = new RedisClientManager(context.credentials(), settings);
        var queryParser = new RedisCommandParser(context.messages());
        this.clientManager = manager;
        this.parser = queryParser;
        this.executor = new RedisQueryExecutor(manager, queryParser, new RedisResultMapper(),
                new RedisExceptionTranslator(context.messages()), context.messages(), context.clock());
        this.connectionProbe = new RedisConnectionProbe(context.credentials(), settings);
        this.schemaIntrospector = new RedisSchemaIntrospector(context.credentials(), settings);
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
            throw new IllegalStateException("RedisQueryEngine used before initialize()");
        }
        return component;
    }
}
