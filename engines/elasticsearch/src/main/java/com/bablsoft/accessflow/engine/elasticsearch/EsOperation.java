package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.QueryType;

/**
 * The supported Elasticsearch / OpenSearch operations the AccessFlow query envelope classifies
 * onto the host's {@link QueryType} model so permissions, routing policies and approval plans
 * apply unchanged. The {@code get} / {@code mget} command keys are not represented here: the parser
 * lowers a read-by-id into a {@link #SEARCH} with an {@code ids} query so there is a single
 * row-security and execution path (a direct {@code GET /index/_doc/id} cannot be filtered).
 *
 * <ul>
 *   <li>{@link #SEARCH} / {@link #COUNT} → {@link QueryType#SELECT}</li>
 *   <li>{@link #INDEX} / {@link #BULK} → {@link QueryType#INSERT}</li>
 *   <li>{@link #UPDATE_BY_QUERY} → {@link QueryType#UPDATE}</li>
 *   <li>{@link #DELETE_BY_QUERY} → {@link QueryType#DELETE}</li>
 *   <li>{@link #CREATE_INDEX} / {@link #PUT_MAPPING} / {@link #DELETE_INDEX} → {@link QueryType#DDL}</li>
 * </ul>
 */
enum EsOperation {

    SEARCH(QueryType.SELECT),
    COUNT(QueryType.SELECT),
    INDEX(QueryType.INSERT),
    BULK(QueryType.INSERT),
    UPDATE_BY_QUERY(QueryType.UPDATE),
    DELETE_BY_QUERY(QueryType.DELETE),
    CREATE_INDEX(QueryType.DDL),
    PUT_MAPPING(QueryType.DDL),
    DELETE_INDEX(QueryType.DDL);

    private final QueryType queryType;

    EsOperation(QueryType queryType) {
        this.queryType = queryType;
    }

    QueryType queryType() {
        return queryType;
    }

    boolean isRead() {
        return this == SEARCH || this == COUNT;
    }

    boolean isWrite() {
        return this == INDEX || this == BULK;
    }
}
