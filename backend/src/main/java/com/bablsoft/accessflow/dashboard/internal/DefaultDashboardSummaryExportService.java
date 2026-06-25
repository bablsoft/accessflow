package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.dashboard.api.DashboardSummaryExport;
import com.bablsoft.accessflow.dashboard.api.DashboardSummaryExportService;
import com.bablsoft.accessflow.dashboard.api.DashboardSummaryFormat;
import com.bablsoft.accessflow.dashboard.api.DashboardWeeklySummary;
import com.bablsoft.accessflow.security.api.ExportSignatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Renders a user's weekly dashboard summary to a signed PDF/CSV (AF-498): build → render → SHA-256 →
 * RSA sign → record a {@code DASHBOARD_SUMMARY_EXPORTED} audit entry (chaining the export's hash into
 * the tamper-evident log) → return the signed artifact. Mirrors {@code DefaultComplianceExportService};
 * the audit write is integrity-critical and is NOT swallowed.
 */
@Service
@RequiredArgsConstructor
class DefaultDashboardSummaryExportService implements DashboardSummaryExportService {

    private final DashboardWeeklySummaryBuilder summaryBuilder;
    private final DashboardSummaryPdfWriter pdfWriter;
    private final DashboardSummaryCsvWriter csvWriter;
    private final ExportSignatureService signatureService;
    private final AuditLogService auditLogService;

    @Override
    public DashboardSummaryExport export(UUID organizationId, UUID userId, LocalDate week,
                                         DashboardSummaryFormat format, UUID actorId, String ipAddress,
                                         String userAgent) {
        DashboardWeeklySummary summary = summaryBuilder.build(organizationId, userId, week);
        byte[] content = render(summary, format);
        String sha256 = sha256Hex(content);
        String signature = signatureService.sign(content);
        String algorithm = signatureService.algorithm();

        recordExportAudit(summary, format, actorId, ipAddress, userAgent, sha256, signature, algorithm);

        return new DashboardSummaryExport(content, filename(summary, format), contentType(format),
                sha256, signature, algorithm);
    }

    private byte[] render(DashboardWeeklySummary summary, DashboardSummaryFormat format) {
        return format == DashboardSummaryFormat.PDF
                ? pdfWriter.write(summary)
                : csvWriter.write(summary);
    }

    private void recordExportAudit(DashboardWeeklySummary summary, DashboardSummaryFormat format,
                                   UUID actorId, String ipAddress, String userAgent, String sha256,
                                   String signature, String algorithm) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("report_type", "weekly_dashboard_summary");
        metadata.put("format", format.name());
        metadata.put("week_start", String.valueOf(summary.weekStart()));
        metadata.put("week_end", String.valueOf(summary.weekEnd()));
        metadata.put("total_queries", summary.totalQueries());
        metadata.put("content_sha256", sha256);
        metadata.put("signature", signature);
        metadata.put("signature_algorithm", algorithm);

        // Integrity-critical: a failed audit write must fail the export (do not swallow).
        auditLogService.record(new AuditEntry(
                AuditAction.DASHBOARD_SUMMARY_EXPORTED,
                AuditResourceType.DASHBOARD_SUMMARY,
                summary.userId(),
                summary.organizationId(),
                actorId,
                metadata,
                ipAddress,
                userAgent));
    }

    private static String filename(DashboardWeeklySummary summary, DashboardSummaryFormat format) {
        var ext = format == DashboardSummaryFormat.PDF ? "pdf" : "csv";
        return "dashboard-summary-" + summary.weekStart() + "." + ext;
    }

    private static String contentType(DashboardSummaryFormat format) {
        return format == DashboardSummaryFormat.PDF ? "application/pdf" : "text/csv; charset=utf-8";
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
