package com.bablsoft.accessflow.core.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Request to read a bounded, governance-applied sample of rows from a single table/collection
 * (issue AF-443). Unlike {@link QueryExecutionRequest} it carries no submitted query text — the
 * target is named by {@code schema} (optional; {@code null} for engines without a schema concept)
 * and {@code table}, which the host has already validated against introspection (an allow-list of
 * the datasource's own metadata). The same row-security directives and column masks a normal query
 * would receive are resolved by the host and applied by the executor/engine, so a masked column
 * never returns a raw value and row-level security filters the sample exactly as the proxy path.
 */
public record SampleTableRequest(
        UUID datasourceId,
        String schema,
        String table,
        List<String> restrictedColumns,
        List<ColumnMaskDirective> columnMasks,
        List<RowSecurityDirective> rowSecurityPredicates,
        Integer maxRowsOverride,
        Duration statementTimeoutOverride) {

    public SampleTableRequest {
        Objects.requireNonNull(datasourceId, "datasourceId");
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
        if (maxRowsOverride != null && maxRowsOverride <= 0) {
            throw new IllegalArgumentException("maxRowsOverride must be positive");
        }
        if (statementTimeoutOverride != null
                && (statementTimeoutOverride.isNegative() || statementTimeoutOverride.isZero())) {
            throw new IllegalArgumentException("statementTimeoutOverride must be positive");
        }
        restrictedColumns = restrictedColumns == null ? List.of() : List.copyOf(restrictedColumns);
        columnMasks = columnMasks == null ? List.of() : List.copyOf(columnMasks);
        rowSecurityPredicates = rowSecurityPredicates == null
                ? List.of() : List.copyOf(rowSecurityPredicates);
    }

    public SampleTableRequest(UUID datasourceId, String schema, String table,
                              Integer maxRowsOverride, Duration statementTimeoutOverride) {
        this(datasourceId, schema, table, List.of(), List.of(), List.of(),
                maxRowsOverride, statementTimeoutOverride);
    }
}
