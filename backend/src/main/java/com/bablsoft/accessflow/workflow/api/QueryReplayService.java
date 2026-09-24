package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.ClientApplication;
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
                         String userAgent,
                         /** The human an API-key caller acts for (#874); null for a human. */
                         UUID onBehalfOfUserId,
                         /** The calling application (#938); null when unknown. */
                         ClientApplication application) {

        /** Backward-compatible constructor without the #938 calling application. */
        public ReplayCommand(UUID originalQueryId, UUID targetDatasourceId, UUID callerUserId,
                             UUID callerOrganizationId, boolean isAdmin, String ipAddress,
                             String userAgent, UUID onBehalfOfUserId) {
            this(originalQueryId, targetDatasourceId, callerUserId, callerOrganizationId, isAdmin,
                    ipAddress, userAgent, onBehalfOfUserId, null);
        }

        /** Backward-compatible constructor without the #874 on-behalf-of principal. */
        public ReplayCommand(UUID originalQueryId, UUID targetDatasourceId, UUID callerUserId,
                             UUID callerOrganizationId, boolean isAdmin, String ipAddress,
                             String userAgent) {
            this(originalQueryId, targetDatasourceId, callerUserId, callerOrganizationId, isAdmin,
                    ipAddress, userAgent, null, null);
        }
    }

    record ReplayResult(UUID newQueryId, QueryStatus status, String sourceSchemaHash,
                        String targetSchemaHash, UUID sourceDatasourceId, UUID targetDatasourceId) {
    }
}
