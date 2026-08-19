package com.bablsoft.accessflow.compliance.internal.web;

import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.compliance.api.ComplianceReportFormat;
import com.bablsoft.accessflow.compliance.api.ResultExportDeniedException;
import com.bablsoft.accessflow.compliance.api.ResultExportNotFoundException;
import com.bablsoft.accessflow.compliance.api.ResultExportService;
import com.bablsoft.accessflow.compliance.api.ResultExportUnavailableException;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.security.api.JwtClaims;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Result-set export endpoints (#626). Pathed under {@code /queries/{id}/results} but served from
 * the compliance module — the workflow module cannot depend on compliance (compliance already
 * depends on {@code workflow.api}, so the reverse edge would be a cycle). Authorization is the
 * results endpoint's own semantic (QUERY_ADMIN or submitter, enforced in the service with
 * 404-hiding), not an admin authority.
 */
@RestController
@RequestMapping("/api/v1/queries/{queryId}/results")
@Tag(name = "Result export",
        description = "Signed, watermark-governed exports of persisted query results")
@RequiredArgsConstructor
class ResultExportController {

    private final ResultExportService resultExportService;
    private final MessageSource messageSource;

    @GetMapping("/export")
    @Operation(summary = "Download the persisted result set as a signed, watermark-governed "
            + "CSV or PDF; every export writes a RESULT_EXPORTED audit row")
    @ApiResponse(responseCode = "200", description = "Signed export stream")
    @ApiResponse(responseCode = "403", description = "The effective export policy denies the export")
    @ApiResponse(responseCode = "404", description = "Query not found, not visible, or no stored results")
    @ApiResponse(responseCode = "422", description = "Query is not a SELECT")
    void export(
            @PathVariable UUID queryId,
            @Parameter(description = "Export format")
            @RequestParam(defaultValue = "CSV") ComplianceReportFormat format,
            Authentication authentication,
            RequestAuditContext auditContext,
            HttpServletResponse response) throws IOException {
        var caller = (JwtClaims) authentication.getPrincipal();
        var export = resultExportService.export(
                caller.organizationId(), queryId, format,
                caller.userId(), caller.email(), caller.has(Permission.QUERY_ADMIN),
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

    @GetMapping("/export-decision")
    @Operation(summary = "The caller's effective export-policy decision for this query's results "
            + "— backs the export button state")
    @ApiResponse(responseCode = "200", description = "Effective export decision")
    @ApiResponse(responseCode = "404", description = "Query not found, not visible, or no stored results")
    @ApiResponse(responseCode = "422", description = "Query is not a SELECT")
    ResultExportDecisionResponse exportDecision(@PathVariable UUID queryId,
                                                Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var decision = resultExportService.decisionFor(caller.organizationId(), queryId,
                caller.userId(), caller.has(Permission.QUERY_ADMIN));
        return ResultExportDecisionResponse.from(decision);
    }

    @ExceptionHandler(ResultExportNotFoundException.class)
    ProblemDetail handleNotFound(ResultExportNotFoundException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                msg("error.result_export_not_found"));
        pd.setProperty("error", "QUERY_REQUEST_NOT_FOUND");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(ResultExportUnavailableException.class)
    ProblemDetail handleUnavailable(ResultExportUnavailableException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                msg("error.result_export_not_select"));
        pd.setProperty("error", "RESULTS_NOT_AVAILABLE");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    @ExceptionHandler(ResultExportDeniedException.class)
    ProblemDetail handleDenied(ResultExportDeniedException ex) {
        var detail = ex.classificationsPresent().isEmpty()
                ? msg("error.result_export_denied")
                : msg("error.result_export_denied_classified", ex.classificationsPresent().stream()
                        .map(Enum::name)
                        .collect(Collectors.joining(", ")));
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail);
        pd.setProperty("error", "RESULT_EXPORT_DENIED");
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
