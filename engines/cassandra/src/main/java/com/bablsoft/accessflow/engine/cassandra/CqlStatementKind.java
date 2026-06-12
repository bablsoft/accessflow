package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.QueryType;

/**
 * The CQL statement classes the engine accepts, each mapped onto the engine-neutral
 * {@link QueryType} so permissions, routing policies, and approval plans apply unchanged. CQL's
 * lightweight transactions ({@code INSERT … IF NOT EXISTS}, {@code UPDATE/DELETE … IF …}) classify
 * as their base DML type; {@code TRUNCATE} and every {@code CREATE/ALTER/DROP} of a table, index,
 * keyspace, type, or materialized view are DDL.
 */
enum CqlStatementKind {
    SELECT(QueryType.SELECT),
    INSERT(QueryType.INSERT),
    UPDATE(QueryType.UPDATE),
    DELETE(QueryType.DELETE),
    DDL(QueryType.DDL);

    private final QueryType queryType;

    CqlStatementKind(QueryType queryType) {
        this.queryType = queryType;
    }

    QueryType queryType() {
        return queryType;
    }

    boolean isRead() {
        return this == SELECT;
    }
}
