package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.QueryType;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record QueryExecutionRequest(
        UUID datasourceId,
        String sql,
        QueryType queryType,
        Integer maxRowsOverride,
        Duration statementTimeoutOverride,
        List<String> restrictedColumns,
        boolean transactional,
        List<String> statements) {

    public QueryExecutionRequest {
        Objects.requireNonNull(datasourceId, "datasourceId");
        Objects.requireNonNull(queryType, "queryType");
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        if (maxRowsOverride != null && maxRowsOverride <= 0) {
            throw new IllegalArgumentException("maxRowsOverride must be positive");
        }
        if (statementTimeoutOverride != null
                && (statementTimeoutOverride.isNegative() || statementTimeoutOverride.isZero())) {
            throw new IllegalArgumentException("statementTimeoutOverride must be positive");
        }
        restrictedColumns = restrictedColumns == null ? List.of() : List.copyOf(restrictedColumns);
        statements = statements == null || statements.isEmpty()
                ? List.of(sql)
                : List.copyOf(statements);
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

    public QueryExecutionRequest(UUID datasourceId, String sql, QueryType queryType,
                                 Integer maxRowsOverride, Duration statementTimeoutOverride) {
        this(datasourceId, sql, queryType, maxRowsOverride, statementTimeoutOverride,
                List.of(), false, null);
    }

    public QueryExecutionRequest(UUID datasourceId, String sql, QueryType queryType,
                                 Integer maxRowsOverride, Duration statementTimeoutOverride,
                                 List<String> restrictedColumns) {
        this(datasourceId, sql, queryType, maxRowsOverride, statementTimeoutOverride,
                restrictedColumns, false, null);
    }
}
