package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.compliance.api.ComplianceExportService;
import com.bablsoft.accessflow.compliance.api.ComplianceReport;
import com.bablsoft.accessflow.compliance.api.ComplianceReportFormat;
import com.bablsoft.accessflow.compliance.api.ComplianceReportRequest;
import com.bablsoft.accessflow.compliance.api.ComplianceReportService;
import com.bablsoft.accessflow.compliance.api.SignedExport;
import com.bablsoft.accessflow.security.api.ExportSignatureService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;

/**
 * Renders a compliance report to a signed PDF/CSV export (#459): generate → render → SHA-256 → RSA
 * sign → record a {@code COMPLIANCE_REPORT_EXPORTED} audit entry (chaining the export's hash into
 * the tamper-evident log) → return the signed artifact. The audit write is integrity-critical and
 * is NOT swallowed — if it fails, the export fails.
 */
@Service
@RequiredArgsConstructor
class DefaultComplianceExportService implements ComplianceExportService {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ComplianceReportService reportService;
    private final CompliancePdfWriter pdfWriter;
    private final ComplianceCsvWriter csvWriter;
    private final ExportSignatureService signatureService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @Override
    public SignedExport export(UUID organizationId, ComplianceReportRequest request,
                               ComplianceReportFormat format, UUID actorId, String ipAddress,
                               String userAgent) {
        ComplianceReport report = reportService.generate(organizationId, request);
        byte[] content = render(report, format);
        String sha256 = sha256Hex(content);
        String signature = signatureService.sign(content);
        String algorithm = signatureService.algorithm();

        recordExportAudit(report, format, actorId, ipAddress, userAgent, sha256, signature, algorithm);

        return new SignedExport(content, filename(report, format), contentType(format), sha256,
                signature, algorithm, report.truncated());
    }

    private byte[] render(ComplianceReport report, ComplianceReportFormat format) {
        return format == ComplianceReportFormat.PDF
                ? pdfWriter.write(report)
                : csvWriter.write(report);
    }

    private void recordExportAudit(ComplianceReport report, ComplianceReportFormat format,
                                   UUID actorId, String ipAddress, String userAgent,
                                   String sha256, String signature, String algorithm) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("report_type", report.type().name());
        metadata.put("format", format.name());
        metadata.put("period_from", String.valueOf(report.periodFrom()));
        metadata.put("period_to", String.valueOf(report.periodTo()));
        if (report.datasourceId() != null) {
            metadata.put("datasource_id", report.datasourceId().toString());
        }
        metadata.put("row_count", report.rowCount());
        metadata.put("truncated", report.truncated());
        metadata.put("content_sha256", sha256);
        metadata.put("signature", signature);
        metadata.put("signature_algorithm", algorithm);

        // Integrity-critical: a failed audit write must fail the export (do not swallow).
        auditLogService.record(new AuditEntry(
                AuditAction.COMPLIANCE_REPORT_EXPORTED,
                AuditResourceType.COMPLIANCE_REPORT,
                null,
                report.organizationId(),
                actorId,
                metadata,
                ipAddress,
                userAgent));
    }

    private String filename(ComplianceReport report, ComplianceReportFormat format) {
        var type = report.type().name().toLowerCase(Locale.ROOT).replace('_', '-');
        var ext = format == ComplianceReportFormat.PDF ? "pdf" : "csv";
        return "compliance-" + type + "-" + FILE_STAMP.format(Instant.now(clock)) + "." + ext;
    }

    private static String contentType(ComplianceReportFormat format) {
        return format == ComplianceReportFormat.PDF ? "application/pdf" : "text/csv; charset=utf-8";
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
