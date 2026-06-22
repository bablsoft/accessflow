package com.bablsoft.accessflow.ai.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read API for behavioural anomalies consumed by other modules: the workflow routing engine uses
 * {@link #hasActiveAnomaly} to raise the {@code anomalyActive} routing signal on a flagged user's
 * next query; the realtime / notifications fan-out re-reads detail via {@link #findById}; the
 * frontend badges read {@link #badgeForUser} / {@link #badgeForUserDatasource}.
 */
public interface BehaviorAnomalyLookupService {

    /** True when the (user, datasource) has at least one OPEN anomaly in the organization. */
    boolean hasActiveAnomaly(UUID organizationId, UUID userId, UUID datasourceId);

    /** OPEN-anomaly badge for a user across all datasources. */
    AnomalyBadgeView badgeForUser(UUID organizationId, UUID userId);

    /** OPEN-anomaly badge for a user scoped to one datasource. */
    AnomalyBadgeView badgeForUserDatasource(UUID organizationId, UUID userId, UUID datasourceId);

    /** Full anomaly detail, org-scoped. Empty when not found or cross-tenant. */
    Optional<BehaviorAnomalyView> findById(UUID organizationId, UUID anomalyId);
}
