package com.bablsoft.accessflow.compliance.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One lifecycle run (retention or erasure action) for the
 * {@link ComplianceReportType#RETENTION_ADHERENCE} report (AF-499). {@code kind}/{@code action}/
 * {@code status} are the enum names; {@code method} is the human-readable applied method (e.g.
 * {@code SOFT_DELETE(deleted_at)}).
 */
public record RetentionAdherenceReportRow(
        UUID runId,
        UUID datasourceId,
        String datasourceName,
        String kind,
        String action,
        String status,
        String method,
        long affectedRows,
        UUID policyId,
        UUID deletionRequestId,
        Instant finishedAt,
        Instant createdAt) {
}
