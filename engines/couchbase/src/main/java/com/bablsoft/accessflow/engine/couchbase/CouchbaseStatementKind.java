package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.QueryType;

/**
 * The SQL++ statement classes the engine accepts, each mapped onto the engine-neutral
 * {@link QueryType} so permissions, routing policies, and approval plans apply unchanged:
 * {@code UPSERT} governs like an INSERT, {@code MERGE} like an UPDATE.
 */
enum CouchbaseStatementKind {
    SELECT(QueryType.SELECT),
    INSERT(QueryType.INSERT),
    UPSERT(QueryType.INSERT),
    UPDATE(QueryType.UPDATE),
    MERGE(QueryType.UPDATE),
    DELETE(QueryType.DELETE),
    DDL(QueryType.DDL);

    private final QueryType queryType;

    CouchbaseStatementKind(QueryType queryType) {
        this.queryType = queryType;
    }

    QueryType queryType() {
        return queryType;
    }

    boolean isRead() {
        return this == SELECT;
    }
}
