package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.QueryType;

/**
 * The PartiQL statement classes the DynamoDB engine accepts, each mapped onto the engine-neutral
 * {@link QueryType} so permissions, routing policies, and approval plans apply unchanged. Table
 * management ({@code CreateTable} / {@code DeleteTable} / {@code UpdateTable}) arrives as a JSON
 * command document rather than PartiQL and classifies as {@link #DDL}
 * ({@link DynamoDbDdlCommand}).
 */
enum PartiQlStatementKind {
    SELECT(QueryType.SELECT),
    INSERT(QueryType.INSERT),
    UPDATE(QueryType.UPDATE),
    DELETE(QueryType.DELETE),
    DDL(QueryType.DDL);

    private final QueryType queryType;

    PartiQlStatementKind(QueryType queryType) {
        this.queryType = queryType;
    }

    QueryType queryType() {
        return queryType;
    }

    boolean isRead() {
        return this == SELECT;
    }
}
