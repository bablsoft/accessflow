package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
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
 * The {@link QueryEngine} SPI implementation for Elasticsearch — the entry point the host discovers
 * via {@link java.util.ServiceLoader} from the shaded plugin JAR (see
 * {@code META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine}).
 * {@link #initialize(QueryEngineContext)} performs the wiring Spring DI would otherwise do: settings
 * are parsed from the context's config map, and the parser / row-security applier / result mapper /
 * exception translator / client manager are composed into the executor and the admin probes.
 *
 * <p>Not {@code final}: {@link OpenSearchQueryEngine} subclasses it to serve the wire-compatible
 * OpenSearch connector from the very same JAR, changing only {@link #engineId()} and {@link
 * #flavor()} (the low-level REST client used). The shared logic operates on raw JSON, so it is
 * identical across both.
 */
public class ElasticsearchQueryEngine implements QueryEngine {

    static final String ENGINE_ID = "elasticsearch";

    private volatile EsQueryParser parser;
    private volatile EsQueryExecutor executor;
    private volatile EsConnectionProbe connectionProbe;
    private volatile EsSchemaIntrospector schemaIntrospector;
    private volatile SearchClientManager clientManager;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public ElasticsearchQueryEngine() {
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    /** Which low-level REST client backs this provider; overridden by {@link OpenSearchQueryEngine}. */
    protected TransportFlavor flavor() {
        return TransportFlavor.ELASTICSEARCH;
    }

    @Override
    public void initialize(QueryEngineContext context) {
        Objects.requireNonNull(context, "context must not be null");
        var settings = ElasticsearchEngineSettings.from(context.config());
        var factory = new SearchTransportFactory(context.credentials(), settings, flavor());
        var manager = new SearchClientManager(factory);
        var queryParser = new EsQueryParser(context.messages());
        this.clientManager = manager;
        this.parser = queryParser;
        this.executor = new EsQueryExecutor(manager, queryParser,
                new EsRowSecurityApplier(context.messages()), new EsResultMapper(),
                new EsExceptionTranslator(context.messages()), context.clock());
        this.connectionProbe = new EsConnectionProbe(factory);
        this.schemaIntrospector = new EsSchemaIntrospector(factory);
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
        // engineId() is polymorphic — "elasticsearch" here, "opensearch" in the subclass — so the
        // result is stamped with the right engine for the shared executor.
        return initialized(executor).dryRun(engineId(), request.request(), request.descriptor(),
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
            throw new IllegalStateException("Search engine used before initialize()");
        }
        return component;
    }
}
