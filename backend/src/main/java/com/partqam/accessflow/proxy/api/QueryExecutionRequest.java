package com.partqam.accessflow.proxy.api;

import com.partqam.accessflow.core.api.QueryType;

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
        List<String> restrictedColumns) {

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
    }

    public QueryExecutionRequest(UUID datasourceId, String sql, QueryType queryType,
                                 Integer maxRowsOverride, Duration statementTimeoutOverride) {
        this(datasourceId, sql, queryType, maxRowsOverride, statementTimeoutOverride, List.of());
    }
}
