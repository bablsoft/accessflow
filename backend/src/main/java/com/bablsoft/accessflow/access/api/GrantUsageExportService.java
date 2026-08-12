package com.bablsoft.accessflow.access.api;

import java.util.UUID;

/**
 * Renders the over-provisioned access report as CSV (#625) — one row per standing grant with its
 * usage evidence and recommendation, for offline least-privilege review and audit evidence.
 *
 * <p>Capped at {@code accessflow.access.usage.max-report-rows}; beyond it the export is flagged
 * truncated so the caller can surface that rather than silently shipping a partial inventory.
 */
public interface GrantUsageExportService {

    UsageExport export(UUID organizationId, GrantUsageReportQuery query);

    /**
     * @param content   UTF-8 CSV bytes
     * @param filename  suggested download filename
     * @param rowCount  number of grant rows written
     * @param truncated true when the organization had more matching grants than the export cap
     */
    record UsageExport(byte[] content, String filename, int rowCount, boolean truncated) {
    }
}
