package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.QueryStatus;

import java.util.UUID;

/**
 * Replays an executed query's immutable snapshot against a target (test) datasource (AF-449). The exact
 * SQL is re-submitted through the full review workflow — replay never bypasses approval, and because the
 * new query's submitter is the replaying caller, the self-approval guard still applies. The replay is
 * distinctly audited with {@code trigger=replay}.
 */
public interface QueryReplayService {

    /**
     * Validates schema compatibility and re-submits the snapshot against the target datasource.
     *
     * @throws QuerySnapshotNotFoundException if no execution snapshot exists for the original query
     *         (it never executed, or it is in another organization).
     * @throws ReplaySchemaIncompatibleException if the target is a different engine, is missing tables
     *         the query references, or its schema cannot be introspected.
     */
    ReplayResult replay(ReplayCommand command);

    record ReplayCommand(UUID originalQueryId, UUID targetDatasourceId, UUID callerUserId,
                         UUID callerOrganizationId, boolean isAdmin, String ipAddress,
                         String userAgent) {
    }

    record ReplayResult(UUID newQueryId, QueryStatus status, String sourceSchemaHash,
                        String targetSchemaHash, UUID sourceDatasourceId, UUID targetDatasourceId) {
    }
}
