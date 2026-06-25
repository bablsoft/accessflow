package com.bablsoft.accessflow.dashboard.api;

import com.bablsoft.accessflow.core.api.MyQueryTrendsRaw;
import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-aggregation surface for the personalized dashboard (AF-498). Every method is self-scoped to
 * {@code (organizationId, userId)} — a user only ever sees their own data; no admin role required.
 */
public interface DashboardService {

    /**
     * The dashboard summary for the current user. {@code role} is the caller's role, needed to scope
     * the reviewer pending-approvals queue (non-reviewers see zero).
     */
    DashboardSummary summary(UUID organizationId, UUID userId, UserRoleType role);

    /**
     * Day-bucketed status/risk trend series for the user's own queries. {@code from}/{@code to} are
     * optional; when null they default to {@code now-30d … now}.
     */
    MyQueryTrendsRaw trends(UUID organizationId, UUID userId, Instant from, Instant to);
}
