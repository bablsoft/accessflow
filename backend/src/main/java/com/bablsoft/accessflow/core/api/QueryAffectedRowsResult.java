package com.bablsoft.accessflow.core.api;

import java.time.Duration;

/**
 * Result of a governed affected-row count for an UPDATE/DELETE (issue AF-624): how many rows the
 * statement would touch, computed by the engine's native non-mutating count (e.g. MongoDB
 * {@code countDocuments}, SQL++ / relational {@code SELECT COUNT(*)}, Cypher
 * {@code MATCH … RETURN count(*)}, Elasticsearch {@code _count}) with the request's row-security
 * directives applied. {@code supported} is {@code false} for engines with no count concept or
 * statement shapes the engine cannot provably count; {@code unsupportedReason} then carries an
 * optional engine-supplied explanation (the host localizes a generic one when absent).
 */
public record QueryAffectedRowsResult(
        boolean supported,
        String engineId,
        Long affectedRows,
        Duration duration,
        String unsupportedReason) {

    /** Degraded result for an engine that cannot count affected rows. */
    public static QueryAffectedRowsResult unsupported(String engineId) {
        return unsupported(engineId, null);
    }

    /** Degraded result carrying an engine-supplied reason (e.g. "join shapes cannot be counted"). */
    public static QueryAffectedRowsResult unsupported(String engineId, String reason) {
        return new QueryAffectedRowsResult(false, engineId, null, Duration.ZERO, reason);
    }

    public static QueryAffectedRowsResult of(String engineId, long affectedRows,
                                             Duration duration) {
        return new QueryAffectedRowsResult(true, engineId, affectedRows, duration, null);
    }
}
