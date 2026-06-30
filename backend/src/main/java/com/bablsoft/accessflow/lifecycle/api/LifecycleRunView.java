package com.bablsoft.accessflow.lifecycle.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of a lifecycle run (a staged/executed retention or erasure action) — the cross-module
 * payload backing the retention-adherence / deletion-history compliance report (AF-499).
 */
public record LifecycleRunView(
        UUID id,
        UUID organizationId,
        UUID datasourceId,
        LifecycleRunKind kind,
        UUID policyId,
        UUID deletionRequestId,
        LifecycleRunStatus status,
        LifecycleAction action,
        long affectedRows,
        String method,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt) {
}
