package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * A suggestion recompute for this datasource is already running somewhere in the cluster (#776) —
 * HTTP 409. Either another replica answered the same request, or the scheduled aggregation job is
 * mid-pass over this datasource.
 */
public class QuerySuggestionRecomputeInProgressException extends RuntimeException {

    private final UUID datasourceId;

    public QuerySuggestionRecomputeInProgressException(UUID datasourceId) {
        super("Query suggestion recompute already running for datasource " + datasourceId);
        this.datasourceId = datasourceId;
    }

    public UUID datasourceId() {
        return datasourceId;
    }
}
