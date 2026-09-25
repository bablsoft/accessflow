package com.bablsoft.accessflow.core.api;


import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@code referencedTables} (AF-457) carries the parser's normalized table set (lowercase
 * {@code schema.table} or bare {@code table}) so the proxy can index cached SELECT results for
 * write-invalidation. An empty set means "tables unknown": SELECTs are then never cached and
 * writes purge the whole datasource cache (fail-safe).
 *
 * <p>{@code maxResultBytesOverride} (#942) is the reader's remaining data-budget byte allowance:
 * the proxy trims a SELECT result past it (the first row is always kept) and marks it truncated
 * with {@link SelectExecutionResult#TRUNCATED_DATA_BUDGET}. {@code null} leaves only the global cap.
 */
public record QueryExecutionRequest(
        UUID datasourceId,
        String sql,
        QueryType queryType,
        Integer maxRowsOverride,
        Duration statementTimeoutOverride,
        List<String> restrictedColumns,
        List<ColumnMaskDirective> columnMasks,
        List<RowSecurityDirective> rowSecurityPredicates,
        boolean transactional,
        List<String> statements,
        List<SoftDeleteDirective> softDeleteDirectives,
        Set<String> referencedTables,
        Long maxResultBytesOverride) {

    public QueryExecutionRequest {
        Objects.requireNonNull(datasourceId, "datasourceId");
        Objects.requireNonNull(queryType, "queryType");
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        if (maxRowsOverride != null && maxRowsOverride <= 0) {
            throw new IllegalArgumentException("maxRowsOverride must be positive");
        }
        if (maxResultBytesOverride != null && maxResultBytesOverride <= 0) {
            throw new IllegalArgumentException("maxResultBytesOverride must be positive");
        }
        if (statementTimeoutOverride != null
                && (statementTimeoutOverride.isNegative() || statementTimeoutOverride.isZero())) {
            throw new IllegalArgumentException("statementTimeoutOverride must be positive");
        }
        restrictedColumns = restrictedColumns == null ? List.of() : List.copyOf(restrictedColumns);
        columnMasks = columnMasks == null ? List.of() : List.copyOf(columnMasks);
        rowSecurityPredicates = rowSecurityPredicates == null
                ? List.of() : List.copyOf(rowSecurityPredicates);
        statements = statements == null || statements.isEmpty()
                ? List.of(sql)
                : List.copyOf(statements);
        softDeleteDirectives = softDeleteDirectives == null
                ? List.of() : List.copyOf(softDeleteDirectives);
        referencedTables = referencedTables == null ? Set.of() : Set.copyOf(referencedTables);
        if (transactional) {
            if (queryType != QueryType.INSERT
                    && queryType != QueryType.UPDATE
                    && queryType != QueryType.DELETE) {
                throw new IllegalArgumentException(
                        "transactional requests must classify as INSERT, UPDATE, or DELETE");
            }
            for (String stmt : statements) {
                if (stmt == null || stmt.isBlank()) {
                    throw new IllegalArgumentException(
                            "transactional statements must not be blank");
                }
            }
        }
    }

    /** Backward-compatible constructor without a result-byte override (#942). */
    public QueryExecutionRequest(UUID datasourceId, String sql, QueryType queryType,
                                 Integer maxRowsOverride, Duration statementTimeoutOverride,
                                 List<String> restrictedColumns, List<ColumnMaskDirective> columnMasks,
                                 List<RowSecurityDirective> rowSecurityPredicates,
                                 boolean transactional, List<String> statements,
                                 List<SoftDeleteDirective> softDeleteDirectives,
                                 Set<String> referencedTables) {
        this(datasourceId, sql, queryType, maxRowsOverride, statementTimeoutOverride,
                restrictedColumns, columnMasks, rowSecurityPredicates, transactional, statements,
                softDeleteDirectives, referencedTables, null);
    }

    /**
     * Returns a copy whose row and result-byte caps are lowered to the given allowance (#942);
     * a null argument leaves that cap unchanged, and neither can ever raise an existing cap.
     */
    public QueryExecutionRequest withAllowance(Long maxRows, Long maxBytes) {
        Integer rows = maxRowsOverride;
        if (maxRows != null) {
            int allowance = (int) Math.min(Integer.MAX_VALUE, Math.max(1, maxRows));
            rows = rows == null ? allowance : Math.min(rows, allowance);
        }
        Long bytes = maxResultBytesOverride;
        if (maxBytes != null) {
            long allowance = Math.max(1, maxBytes);
            bytes = bytes == null ? allowance : Math.min(bytes, allowance);
        }
        return new QueryExecutionRequest(datasourceId, sql, queryType, rows, statementTimeoutOverride,
                restrictedColumns, columnMasks, rowSecurityPredicates, transactional, statements,
                softDeleteDirectives, referencedTables, bytes);
    }

    /** Backward-compatible constructor without referenced tables (defaults to unknown). */
    public QueryExecutionRequest(UUID datasourceId, String sql, QueryType queryType,
                                 Integer maxRowsOverride, Duration statementTimeoutOverride,
                                 List<String> restrictedColumns, List<ColumnMaskDirective> columnMasks,
                                 List<RowSecurityDirective> rowSecurityPredicates,
                                 boolean transactional, List<String> statements,
                                 List<SoftDeleteDirective> softDeleteDirectives) {
        this(datasourceId, sql, queryType, maxRowsOverride, statementTimeoutOverride,
                restrictedColumns, columnMasks, rowSecurityPredicates, transactional, statements,
                softDeleteDirectives, Set.of(), null);
    }

    public QueryExecutionRequest(UUID datasourceId, String sql, QueryType queryType,
                                 Integer maxRowsOverride, Duration statementTimeoutOverride) {
        this(datasourceId, sql, queryType, maxRowsOverride, statementTimeoutOverride,
                List.of(), List.of(), List.of(), false, null, List.of());
    }

    /** Backward-compatible constructor without soft-delete directives (defaults to none). */
    public QueryExecutionRequest(UUID datasourceId, String sql, QueryType queryType,
                                 Integer maxRowsOverride, Duration statementTimeoutOverride,
                                 List<String> restrictedColumns, List<ColumnMaskDirective> columnMasks,
                                 List<RowSecurityDirective> rowSecurityPredicates,
                                 boolean transactional, List<String> statements) {
        this(datasourceId, sql, queryType, maxRowsOverride, statementTimeoutOverride,
                restrictedColumns, columnMasks, rowSecurityPredicates, transactional, statements,
                List.of());
    }

    public QueryExecutionRequest(UUID datasourceId, String sql, QueryType queryType,
                                 Integer maxRowsOverride, Duration statementTimeoutOverride,
                                 List<String> restrictedColumns) {
        this(datasourceId, sql, queryType, maxRowsOverride, statementTimeoutOverride,
                restrictedColumns, List.of(), List.of(), false, null, List.of());
    }

    public QueryExecutionRequest(UUID datasourceId, String sql, QueryType queryType,
                                 Integer maxRowsOverride, Duration statementTimeoutOverride,
                                 List<String> restrictedColumns, boolean transactional,
                                 List<String> statements) {
        this(datasourceId, sql, queryType, maxRowsOverride, statementTimeoutOverride,
                restrictedColumns, List.of(), List.of(), transactional, statements, List.of());
    }
}
