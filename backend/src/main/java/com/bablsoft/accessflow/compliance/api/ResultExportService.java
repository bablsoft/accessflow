package com.bablsoft.accessflow.compliance.api;

import java.util.UUID;

/**
 * Renders a query's persisted result snapshot to a signed, watermark-governed CSV/PDF export
 * (#626): visibility check → policy decision → row cap → render (watermark baked into the bytes)
 * → SHA-256 → RSA sign → fail-hard {@code RESULT_EXPORTED} audit → sensitive-export event when
 * classified. Mirrors the {@link ComplianceExportService} pipeline; like there, the audit write
 * is integrity-critical — if it fails, the export fails.
 */
public interface ResultExportService {

    /**
     * @param actorUserId       the exporter (visibility check + audit attribution)
     * @param actorEmail        the exporter's email, stamped into the watermark
     * @param actorHasQueryAdmin whether the caller holds {@code QUERY_ADMIN} (may export any
     *                          query in the organization; otherwise submitter-only)
     * @throws ResultExportNotFoundException    unknown query, foreign organization, caller not
     *                                          allowed to see it, or no persisted result
     * @throws ResultExportUnavailableException the query is not a SELECT
     * @throws ResultExportDeniedException      the effective policy denies the export
     */
    SignedExport export(UUID organizationId, UUID queryRequestId, ComplianceReportFormat format,
                        UUID actorUserId, String actorEmail, boolean actorHasQueryAdmin,
                        String ipAddress, String userAgent);

    /**
     * The caller's effective export decision for the query's result — backs the export button
     * state. Same visibility and availability semantics as {@code export}.
     */
    ExportDecision decisionFor(UUID organizationId, UUID queryRequestId, UUID actorUserId,
                               boolean actorHasQueryAdmin);
}
