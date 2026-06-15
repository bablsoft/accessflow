package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.QueryType;

/**
 * The Cypher statement classes the engine accepts, each mapped onto the engine-neutral
 * {@link QueryType} so permissions, routing policies, and approval plans apply unchanged. Cypher is
 * clause-based rather than verb-prefixed (one statement can mix {@code MATCH … SET … RETURN}), so
 * classification is by the strongest write clause present:
 * {@code DELETE}/{@code DETACH DELETE}/{@code REMOVE} → DELETE; {@code CREATE}/{@code MERGE} →
 * INSERT; {@code SET} → UPDATE; a pure {@code MATCH … RETURN} / {@code SHOW} read → SELECT.
 * Schema commands ({@code CREATE}/{@code DROP}/{@code ALTER} of an INDEX / CONSTRAINT / DATABASE /
 * ALIAS / USER / ROLE) are DDL.
 */
enum CypherStatementKind {
    SELECT(QueryType.SELECT),
    INSERT(QueryType.INSERT),
    UPDATE(QueryType.UPDATE),
    DELETE(QueryType.DELETE),
    DDL(QueryType.DDL);

    private final QueryType queryType;

    CypherStatementKind(QueryType queryType) {
        this.queryType = queryType;
    }

    QueryType queryType() {
        return queryType;
    }

    boolean isRead() {
        return this == SELECT;
    }
}
