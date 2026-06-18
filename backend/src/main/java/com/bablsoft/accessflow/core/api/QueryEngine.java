package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Service-provider interface for a non-JDBC query engine (issue AF-414). An engine plugin ships as
 * a self-contained shaded JAR resolved through the connector catalog — exactly like a JDBC driver
 * JAR — and is discovered from the downloaded JAR's classloader via
 * {@link java.util.ServiceLoader}. Implementations must be plain Java: no Spring, no Jackson, no
 * host-internal types — everything an engine needs from the host (message resolution, credential
 * decryption, tuning knobs, the clock) is handed over in the {@link QueryEngineContext} passed to
 * {@link #initialize(QueryEngineContext)}.
 *
 * <p>Engines operate exclusively on the engine-neutral {@code core.api} DTOs ({@link
 * SqlParseResult}, {@link QueryExecutionRequest}/{@link QueryExecutionResult}, {@link
 * ConnectionTestResult}, {@link DatabaseSchemaView}, {@link DatasourceConnectionDescriptor},
 * {@link RowSecurityDirective}, {@link ColumnMaskDirective}) and signal failures with the existing
 * concrete exception types ({@link InvalidSqlException}, {@link QueryExecutionFailedException},
 * {@link QueryExecutionTimeoutException}, {@link DatasourceConnectionTestException}) so the host's
 * error handling treats every engine uniformly. Row-level security and column masking are applied
 * <em>inside</em> the engine (the host cannot rewrite a non-SQL query); the pure {@link
 * ColumnMasker} helper is part of this SPI surface for that purpose.
 *
 * <p>Lifecycle: instantiated by {@code ServiceLoader} (public no-arg constructor required),
 * {@link #initialize(QueryEngineContext)} is called exactly once before any other method, and the
 * instance is cached by the host for the application lifetime. All methods may be called
 * concurrently from virtual threads after initialization.
 */
public interface QueryEngine {

    /** Stable engine identifier; must equal the connector-catalog id (e.g. {@code "mongodb"}). */
    String engineId();

    /** Called exactly once, before any other method, with the host-provided capabilities. */
    void initialize(QueryEngineContext context);

    /**
     * Parse and validate a submitted query into the engine-neutral {@link SqlParseResult}.
     *
     * @throws InvalidSqlException if the query is blank, unparseable, multi-statement, or uses an
     *         unsupported operation — mapped to HTTP 422 by the host.
     */
    SqlParseResult parse(String query);

    /**
     * Execute an approved query, applying the request's row-security directives and column masks.
     *
     * @throws QueryExecutionException on engine/server failures ({@link
     *         QueryExecutionTimeoutException} for timeouts).
     */
    QueryExecutionResult execute(QueryEngineExecutionRequest request);

    /**
     * Read a bounded, governance-applied sample of rows from a single table/collection (issue
     * AF-443). The engine issues its native "read all rows from this table, capped at
     * {@code effectiveMaxRows}" query and funnels it through the same row-security + column-masking
     * path as {@link #execute(QueryEngineExecutionRequest)}, so a masked column never returns a raw
     * value and row-level security filters the sample. Engines whose row-security model has no
     * per-row meaning for the target (e.g. key-value prefixes) must fail closed — deny with an
     * empty result — when a row-security directive applies. Returns a
     * {@link SelectExecutionResult}.
     *
     * @throws QueryExecutionException on engine/server failures ({@link
     *         QueryExecutionTimeoutException} for timeouts).
     */
    QueryExecutionResult sampleTable(QueryEngineSampleRequest request);

    /**
     * Produce a non-committing execution plan and best-effort estimated row impact for the query
     * (issue AF-445), applying the request's row-security directives so the plan reflects the
     * governed query. Must <em>never</em> execute or mutate data — plan/estimate only (e.g.
     * MongoDB {@code explain} at {@code queryPlanner} verbosity, Couchbase / Neo4j {@code EXPLAIN},
     * Elasticsearch {@code _validate/query?explain}). Engines with no plan concept inherit the
     * default, which returns {@link QueryDryRunResult#unsupported(String)} so the host degrades
     * gracefully with a clear message.
     */
    default QueryDryRunResult dryRun(QueryEngineDryRunRequest request) {
        return QueryDryRunResult.unsupported(engineId());
    }

    /**
     * Short-lived connectivity probe (the engine analogue of the JDBC {@code SELECT 1}).
     *
     * @throws DatasourceConnectionTestException when the target is unreachable or misconfigured.
     */
    ConnectionTestResult testConnection(DatasourceConnectionDescriptor descriptor);

    /**
     * Introspect the datasource into the engine-neutral {@link DatabaseSchemaView}.
     *
     * @throws DatasourceConnectionTestException when the target is unreachable or misconfigured.
     */
    DatabaseSchemaView introspectSchema(DatasourceConnectionDescriptor descriptor);

    /**
     * Drop any cached native client/connection state for the datasource. Called by the host when a
     * datasource's connection config changes or the datasource is deactivated. Idempotent.
     */
    void evictDatasource(UUID datasourceId);

    /** Release all engine resources. Called at most once, on host shutdown. */
    default void shutdown() {
    }
}
