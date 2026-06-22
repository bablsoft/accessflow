package com.bablsoft.accessflow.audit.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only aggregation over {@code audit_log} for behavioural anomaly detection (UBA, AF-383).
 * Lives in the audit module so the JSONB metadata parsing stays with the table that owns it; the AI
 * module consumes only these pure DTOs. Reads only — compatible with the SELECT-only application
 * role on {@code audit_log}.
 */
public interface BehaviorAuditAggregationService {

    /**
     * Distinct (org, user, datasource) subjects that ran a query (executed or failed) in
     * {@code [from, to)}. {@code datasource_id} is read from the enriched audit metadata; rows
     * without it are skipped (they predate AF-383's metadata enrichment).
     */
    List<BehaviorSubjectRef> findActiveSubjects(Instant from, Instant to);

    /**
     * Per-query samples for one subject in {@code [from, to)}, ordered by {@code occurred_at}.
     */
    List<BehaviorAuditSample> samplesFor(UUID organizationId, UUID userId, UUID datasourceId,
                                         Instant from, Instant to);
}
