package com.bablsoft.accessflow.access.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Scope + approval provenance of a single access grant, consumed by the workflow module's
 * grant-covered auto-approval fast-path (#582) and the query-detail read path. {@code approverId},
 * {@code approverEmail}, and {@code approvedAt} describe the final-stage APPROVED decision that
 * granted the request; they are {@code null} when no decision row exists (e.g. bootstrap-seeded
 * grants) or the approver was since deleted.
 */
public record AccessGrantView(
        UUID id,
        UUID organizationId,
        UUID requesterId,
        UUID datasourceId,
        boolean canRead,
        boolean canWrite,
        boolean canDdl,
        List<String> allowedSchemas,
        List<String> allowedTables,
        AccessGrantStatus status,
        Instant expiresAt,
        UUID approverId,
        String approverEmail,
        Instant approvedAt) {
}
