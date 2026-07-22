package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.core.api.QueryType;

/**
 * The Snowflake SQL statement classes the engine accepts, each mapped onto the engine-neutral
 * {@link QueryType} so permissions, routing policies, and approval plans apply unchanged.
 * {@code MERGE} classifies as {@link QueryType#UPDATE} (it is a conditional write against the
 * target table); {@code TRUNCATE} and the accepted {@code CREATE}/{@code ALTER}/{@code DROP}
 * object forms classify as {@link QueryType#DDL}.
 */
enum SnowflakeStatementKind {
    SELECT(QueryType.SELECT),
    INSERT(QueryType.INSERT),
    UPDATE(QueryType.UPDATE),
    DELETE(QueryType.DELETE),
    MERGE(QueryType.UPDATE),
    DDL(QueryType.DDL);

    private final QueryType queryType;

    SnowflakeStatementKind(QueryType queryType) {
        this.queryType = queryType;
    }

    QueryType queryType() {
        return queryType;
    }

    boolean isRead() {
        return this == SELECT;
    }
}
