package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.QueryEngine;
import com.bablsoft.accessflow.core.api.QueryEngineContext;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryEngineSampleRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.SqlParseResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ServiceLoader fixture for {@link DefaultQueryEngineCatalogTest}: the class itself sits on the
 * test classpath (resolved through the parent classloader), while the fixture JAR built by the
 * test contains only the {@code META-INF/services} registration — which is exactly the part of
 * discovery the catalog must read from the plugin JAR's classloader. State is static because
 * ServiceLoader instantiates the provider itself.
 */
public final class FakeQueryEngine implements QueryEngine {

    static volatile String engineId = "mongodb";
    static final List<QueryEngineContext> initializations = new ArrayList<>();
    static final List<UUID> evictions = new ArrayList<>();
    /** When set, {@link #initialize} blocks until the latch opens (for concurrency tests). */
    static volatile java.util.concurrent.CountDownLatch initGate;
    /** When set, {@link #evictDatasource} throws it (for evict-fan-out error handling tests). */
    static volatile RuntimeException evictFailure;

    static void reset(String id) {
        engineId = id;
        initializations.clear();
        evictions.clear();
        initGate = null;
        evictFailure = null;
    }

    public FakeQueryEngine() {
    }

    @Override
    public String engineId() {
        return engineId;
    }

    @Override
    public void initialize(QueryEngineContext context) {
        var gate = initGate;
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        initializations.add(context);
    }

    @Override
    public SqlParseResult parse(String query) {
        throw new UnsupportedOperationException("fixture");
    }

    @Override
    public QueryExecutionResult execute(QueryEngineExecutionRequest request) {
        throw new UnsupportedOperationException("fixture");
    }

    @Override
    public QueryExecutionResult sampleTable(QueryEngineSampleRequest request) {
        throw new UnsupportedOperationException("fixture");
    }

    @Override
    public ConnectionTestResult testConnection(DatasourceConnectionDescriptor descriptor) {
        throw new UnsupportedOperationException("fixture");
    }

    @Override
    public DatabaseSchemaView introspectSchema(DatasourceConnectionDescriptor descriptor) {
        throw new UnsupportedOperationException("fixture");
    }

    @Override
    public void evictDatasource(UUID datasourceId) {
        var failure = evictFailure;
        if (failure != null) {
            throw failure;
        }
        evictions.add(datasourceId);
    }
}
