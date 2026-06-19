package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Records and reads immutable query snapshots (AF-449). A snapshot is written exactly once when a query
 * transitions to {@code EXECUTED}; it captures the exact SQL, the source schema fingerprint, the AI
 * verdict, and the approval decisions for forensic/compliance use and as the source artifact for replay.
 */
public interface QuerySnapshotService {

    /**
     * Records the immutable snapshot for an executed query. Idempotent: a no-op when a snapshot already
     * exists for the query (safe under event redelivery). Never throws to its caller — failures are
     * logged and swallowed so snapshot capture cannot disrupt query execution.
     */
    void recordOnExecution(UUID queryRequestId);

    /** Loads the snapshot for a query, scoped to the organization. */
    Optional<QuerySnapshotView> find(UUID queryRequestId, UUID organizationId);

    /**
     * Returns executed-query snapshots for an organization whose {@code executedAt} falls in the
     * half-open interval {@code [from, to)}, ordered by {@code executedAt} ascending. Optionally
     * restricted to a single {@code datasourceId} (null = all) and to a set of {@code queryTypes}
     * (null or empty = all types). At most {@code limit} rows are returned; the immutable
     * {@code query_snapshots} record is the forensic source for compliance reporting (#459).
     */
    List<QuerySnapshotView> findForPeriod(UUID organizationId, Instant from, Instant to,
                                          UUID datasourceId, Set<QueryType> queryTypes, int limit);
}
