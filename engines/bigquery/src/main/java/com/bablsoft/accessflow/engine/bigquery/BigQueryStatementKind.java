package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.QueryType;

/**
 * The GoogleSQL statement classes the BigQuery engine accepts, each mapped onto the engine-neutral
 * {@link QueryType} so permissions, routing policies, and approval plans apply unchanged.
 * {@code MERGE} classifies as {@link QueryType#UPDATE} (it is a keyed upsert over the target
 * table); {@code TRUNCATE TABLE} and the table/view/materialized-view/schema {@code CREATE} /
 * {@code ALTER} / {@code DROP} forms classify as {@link QueryType#DDL}.
 */
enum BigQueryStatementKind {
    SELECT(QueryType.SELECT),
    INSERT(QueryType.INSERT),
    UPDATE(QueryType.UPDATE),
    DELETE(QueryType.DELETE),
    MERGE(QueryType.UPDATE),
    DDL(QueryType.DDL);

    private final QueryType queryType;

    BigQueryStatementKind(QueryType queryType) {
        this.queryType = queryType;
    }

    QueryType queryType() {
        return queryType;
    }

    boolean isRead() {
        return this == SELECT;
    }
}
