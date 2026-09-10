package com.bablsoft.accessflow.workflow.internal;

import java.util.UUID;

/**
 * The one place the per-datasource suggestion-rebuild lock name is spelled (#776).
 *
 * <p>Both writers take it: the scheduled aggregation, per datasource inside its organisation walk,
 * and the on-demand recompute, on the request thread so the endpoint can answer 202 or 409
 * accurately. They must agree on the string or they do not exclude each other at all — hence one
 * constant rather than two format calls.
 *
 * <p>It shares the ShedLock namespace with {@code @SchedulerLock} names, so it is camelCase like
 * they are.
 */
final class QuerySuggestionLocks {

    private QuerySuggestionLocks() {
    }

    static String forDatasource(UUID datasourceId) {
        return "querySuggestionRebuild:" + datasourceId;
    }
}
