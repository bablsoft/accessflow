package com.bablsoft.accessflow.audit.api;

import java.util.UUID;

/**
 * A distinct (organization, user, datasource) subject that executed or failed a query within a
 * window, derived from {@code audit_log} only. The work-unit enumerated by behavioural anomaly
 * detection (AF-383) — see {@link BehaviorAuditAggregationService#findActiveSubjects}.
 */
public record BehaviorSubjectRef(UUID organizationId, UUID userId, UUID datasourceId) {
}
