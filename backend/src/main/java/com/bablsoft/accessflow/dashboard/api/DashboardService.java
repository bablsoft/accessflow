package com.bablsoft.accessflow.dashboard.api;

import com.bablsoft.accessflow.apigov.api.MyApiRequestTrendsRaw;
import com.bablsoft.accessflow.core.api.MyQueryTrendsRaw;
import com.bablsoft.accessflow.core.api.Permission;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Read-aggregation surface for the personalized dashboard (AF-498). Every method is self-scoped to
 * {@code (organizationId, userId)} — a user only ever sees their own data; no admin role required.
 */
public interface DashboardService {

    /**
     * The dashboard summary for the current user. {@code roleName}/{@code permissions} are the
     * caller's effective role name and permission set (AF-522), needed to scope the reviewer
     * pending-approvals queue (callers without QUERY_REVIEW see zero).
     */
    DashboardSummary summary(UUID organizationId, UUID userId, String roleName,
                             Set<Permission> permissions);

    /**
     * Day-bucketed status/risk trend series for the user's own queries. {@code from}/{@code to} are
     * optional; when null they default to {@code now-30d … now}.
     */
    MyQueryTrendsRaw trends(UUID organizationId, UUID userId, Instant from, Instant to);

    /**
     * Day-bucketed status/risk trend series for the user's own governed API requests (AF-500).
     * {@code from}/{@code to} are optional; when null they default to {@code now-30d … now}.
     */
    MyApiRequestTrendsRaw apiRequestTrends(UUID organizationId, UUID userId, Instant from, Instant to);
}
