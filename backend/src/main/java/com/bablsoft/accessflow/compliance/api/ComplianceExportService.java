package com.bablsoft.accessflow.compliance.api;

import java.util.UUID;

/**
 * Renders a compliance report to a signed PDF/CSV export (#459). Producing an export digitally
 * signs the bytes and records a {@code COMPLIANCE_REPORT_EXPORTED} entry in the tamper-evident
 * audit log carrying the export's SHA-256 and signature — chaining the export's hash into the audit
 * chain. The audit write is integrity-critical: if it fails, the export fails.
 */
public interface ComplianceExportService {

    /**
     * Generates the report for {@code request}, renders it as {@code format}, signs it, records the
     * export in the audit log, and returns the signed artifact.
     *
     * @param actorId     the auditor/admin performing the export (audit attribution)
     * @param ipAddress   request IP (nullable, audit context)
     * @param userAgent   request user-agent (nullable, audit context)
     */
    SignedExport export(UUID organizationId, ComplianceReportRequest request,
                        ComplianceReportFormat format, UUID actorId, String ipAddress,
                        String userAgent);
}
