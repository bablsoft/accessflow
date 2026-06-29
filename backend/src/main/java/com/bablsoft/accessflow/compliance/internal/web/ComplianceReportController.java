package com.bablsoft.accessflow.compliance.internal.web;

import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.compliance.api.ComplianceExportService;
import com.bablsoft.accessflow.compliance.api.ComplianceReportFormat;
import com.bablsoft.accessflow.compliance.api.ComplianceReportRequest;
import com.bablsoft.accessflow.compliance.api.ComplianceReportService;
import com.bablsoft.accessflow.compliance.api.ComplianceReportType;
import com.bablsoft.accessflow.compliance.api.InvalidReportPeriodException;
import com.bablsoft.accessflow.security.api.ExportSignatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only compliance-reporting endpoints (#459), gated to the dedicated AUDITOR role (and ADMIN).
 * Reports are computed from the immutable {@code query_snapshots} forensic record; exports are
 * digitally signed and their hash chained into the tamper-evident audit log.
 */
@RestController
@RequestMapping("/api/v1/admin/compliance")
@PreAuthorize("hasAnyRole('AUDITOR','ADMIN')")
@Tag(name = "Compliance Reporting", description = "Pre-built compliance reports and signed exports "
        + "(AUDITOR / ADMIN only)")
@RequiredArgsConstructor
class ComplianceReportController {

    private final ComplianceReportService reportService;
    private final ComplianceExportService exportService;
    private final ExportSignatureService signatureService;
    private final MessageSource messageSource;

    @GetMapping("/reports/classified-access")
    @Operation(summary = "Executed queries that touched classified (PII/PCI/PHI/GDPR/...) objects "
            + "over a period")
    @ApiResponse(responseCode = "200", description = "Classified-access report")
    @ApiResponse(responseCode = "400", description = "Invalid reporting period")
    @ApiResponse(responseCode = "403", description = "Caller is not an AUDITOR or ADMIN")
    ComplianceReportResponse classifiedAccess(
            @Parameter(description = "Inclusive lower bound on executedAt") @RequestParam Instant from,
            @Parameter(description = "Exclusive upper bound on executedAt") @RequestParam Instant to,
            @Parameter(description = "Optional datasource scope") @RequestParam(required = false)
            UUID datasourceId,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        var request = new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, from, to,
                datasourceId);
        return ComplianceReportResponse.from(reportService.generate(organizationId, request));
    }

    @GetMapping("/reports/regulatory-audit-trail")
    @Operation(summary = "Executed DDL/DELETE operations with approver names over a period")
    @ApiResponse(responseCode = "200", description = "Regulatory audit-trail report")
    @ApiResponse(responseCode = "400", description = "Invalid reporting period")
    @ApiResponse(responseCode = "403", description = "Caller is not an AUDITOR or ADMIN")
    ComplianceReportResponse regulatoryAuditTrail(
            @Parameter(description = "Inclusive lower bound on executedAt") @RequestParam Instant from,
            @Parameter(description = "Exclusive upper bound on executedAt") @RequestParam Instant to,
            @Parameter(description = "Optional datasource scope") @RequestParam(required = false)
            UUID datasourceId,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        var request = new ComplianceReportRequest(ComplianceReportType.REGULATORY_AUDIT_TRAIL, from,
                to, datasourceId);
        return ComplianceReportResponse.from(reportService.generate(organizationId, request));
    }

    @GetMapping("/reports/retention-adherence")
    @Operation(summary = "Retention + erasure lifecycle runs over a period (deletion history, AF-499)")
    @ApiResponse(responseCode = "200", description = "Retention-adherence report")
    @ApiResponse(responseCode = "400", description = "Invalid reporting period")
    @ApiResponse(responseCode = "403", description = "Caller is not an AUDITOR or ADMIN")
    ComplianceReportResponse retentionAdherence(
            @Parameter(description = "Inclusive lower bound on the run's createdAt") @RequestParam Instant from,
            @Parameter(description = "Exclusive upper bound on the run's createdAt") @RequestParam Instant to,
            @Parameter(description = "Optional datasource scope") @RequestParam(required = false)
            UUID datasourceId,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        var request = new ComplianceReportRequest(ComplianceReportType.RETENTION_ADHERENCE, from,
                to, datasourceId);
        return ComplianceReportResponse.from(reportService.generate(organizationId, request));
    }

    @GetMapping("/reports/export")
    @Operation(summary = "Render a compliance report as a digitally-signed PDF or CSV; the export "
            + "hash is chained into the audit log")
    @ApiResponse(responseCode = "200", description = "Signed export stream")
    @ApiResponse(responseCode = "400", description = "Invalid reporting period")
    @ApiResponse(responseCode = "403", description = "Caller is not an AUDITOR or ADMIN")
    void export(
            @Parameter(description = "Report type") @RequestParam ComplianceReportType type,
            @Parameter(description = "Export format") @RequestParam ComplianceReportFormat format,
            @Parameter(description = "Inclusive lower bound on executedAt") @RequestParam Instant from,
            @Parameter(description = "Exclusive upper bound on executedAt") @RequestParam Instant to,
            @Parameter(description = "Optional datasource scope") @RequestParam(required = false)
            UUID datasourceId,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID callerUserId,
            RequestAuditContext auditContext,
            HttpServletResponse response) throws IOException {
        var request = new ComplianceReportRequest(type, from, to, datasourceId);
        var export = exportService.export(organizationId, request, format, callerUserId,
                auditContext == null ? null : auditContext.ipAddress(),
                auditContext == null ? null : auditContext.userAgent());

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(export.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + export.filename() + "\"");
        response.setHeader("X-AccessFlow-Signature", export.signatureBase64());
        response.setHeader("X-AccessFlow-Signature-Algorithm", export.signatureAlgorithm());
        response.setHeader("X-AccessFlow-Content-SHA256", export.contentSha256Hex());
        if (export.truncated()) {
            response.setHeader("X-AccessFlow-Export-Truncated", "true");
        }
        response.getOutputStream().write(export.content());
        response.getOutputStream().flush();
    }

    @GetMapping("/signing-certificate")
    @Operation(summary = "Public key + algorithm for verifying signed compliance exports offline")
    @ApiResponse(responseCode = "200", description = "Signing certificate")
    @ApiResponse(responseCode = "403", description = "Caller is not an AUDITOR or ADMIN")
    SigningCertificateResponse signingCertificate() {
        return new SigningCertificateResponse(signatureService.algorithm(),
                signatureService.publicKeyPem());
    }

    @ExceptionHandler(InvalidReportPeriodException.class)
    ProblemDetail handleInvalidPeriod(InvalidReportPeriodException ex) {
        var detail = messageSource.getMessage(ex.messageKey(), null, LocaleContextHolder.getLocale());
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setProperty("error", "INVALID_REPORT_PERIOD");
        return pd;
    }
}
