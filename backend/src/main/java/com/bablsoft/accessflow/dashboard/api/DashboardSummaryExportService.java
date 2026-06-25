package com.bablsoft.accessflow.dashboard.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Renders a user's weekly dashboard summary to a signed PDF/CSV (AF-498), reusing the deployment's
 * RSA export-signing key (the same one that signs JWTs / compliance exports) and chaining the
 * export's hash into the tamper-evident audit log as {@code DASHBOARD_SUMMARY_EXPORTED}.
 */
public interface DashboardSummaryExportService {

    /**
     * @param organizationId required.
     * @param userId         required; the summary is scoped to this user's own data.
     * @param week           any date within the target ISO week; when null, the current week is used.
     * @param format         PDF or CSV.
     * @param actorId        the authenticated caller (for the audit row); equals {@code userId}.
     * @param ipAddress      nullable request IP for the audit row.
     * @param userAgent      nullable request user-agent for the audit row.
     */
    DashboardSummaryExport export(UUID organizationId, UUID userId, LocalDate week,
                                  DashboardSummaryFormat format, UUID actorId, String ipAddress,
                                  String userAgent);
}
