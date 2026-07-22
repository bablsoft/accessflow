package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.QueryType;

/**
 * The Databricks SQL statement classes the engine accepts, each mapped onto the engine-neutral
 * {@link QueryType} so permissions, routing policies, and approval plans apply unchanged.
 * {@code MERGE} is a mixed-write statement and classifies as {@link QueryType#UPDATE};
 * {@code TRUNCATE} and the {@code CREATE}/{@code ALTER}/{@code DROP} of tables / views / schemas /
 * databases / materialized views classify as {@link QueryType#DDL}.
 */
enum DatabricksStatementKind {
    SELECT(QueryType.SELECT),
    INSERT(QueryType.INSERT),
    UPDATE(QueryType.UPDATE),
    DELETE(QueryType.DELETE),
    MERGE(QueryType.UPDATE),
    DDL(QueryType.DDL);

    private final QueryType queryType;

    DatabricksStatementKind(QueryType queryType) {
        this.queryType = queryType;
    }

    QueryType queryType() {
        return queryType;
    }

    boolean isRead() {
        return this == SELECT;
    }
}
