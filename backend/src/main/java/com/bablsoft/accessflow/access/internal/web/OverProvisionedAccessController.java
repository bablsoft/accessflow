package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.GrantUsageExportService;
import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.access.api.GrantUsageReportQuery;
import com.bablsoft.accessflow.access.api.GrantUsageService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The standing "over-provisioned access" report (#625) — every standing grant with its usage
 * evidence and revocation recommendation, for admins and auditors.
 *
 * <p>Read-only and advisory: nothing here revokes anything, and nothing downstream consumes the
 * recommendation.
 */
@RestController
@RequestMapping("/api/v1/admin/over-provisioned-access")
@PreAuthorize("hasAuthority('PERM_ACCESS_USAGE_REPORT_VIEW')")
@Tag(name = "Over-Provisioned Access",
        description = "Unused and over-scoped standing grants (ADMIN / AUDITOR only)")
@RequiredArgsConstructor
@Slf4j
class OverProvisionedAccessController {

    private final GrantUsageService grantUsageService;
    private final GrantUsageExportService exportService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;
    private final Clock clock;

    @GetMapping
    @Operation(summary = "List standing grants with their usage evidence and revocation "
            + "recommendation, worst first")
    @ApiResponse(responseCode = "200", description = "Page of standing grants")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin or auditor")
    OverProvisionedAccessPageResponse list(
            @RequestParam(name = "resource_kind", required = false) GrantResourceKind resourceKind,
            @RequestParam(name = "recommendation", required = false)
            List<GrantUsageRecommendation> recommendations,
            @RequestParam(name = "resource_id", required = false) UUID resourceId,
            @RequestParam(name = "user_id", required = false) UUID userId,
            Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = grantUsageService.report(caller.organizationId(),
                query(resourceKind, recommendations, resourceId, userId),
                SpringPageableAdapter.toPageRequest(pageable));
        return OverProvisionedAccessPageResponse.from(page, clock.instant());
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @Operation(summary = "Export the over-provisioned access report as CSV")
    @ApiResponse(responseCode = "200", description = "CSV report")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin or auditor")
    ResponseEntity<byte[]> exportCsv(
            @RequestParam(name = "resource_kind", required = false) GrantResourceKind resourceKind,
            @RequestParam(name = "recommendation", required = false)
            List<GrantUsageRecommendation> recommendations,
            @RequestParam(name = "resource_id", required = false) UUID resourceId,
            @RequestParam(name = "user_id", required = false) UUID userId,
            Authentication authentication, RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var filter = query(resourceKind, recommendations, resourceId, userId);
        var export = exportService.export(caller.organizationId(), filter);
        recordExport(caller, filter, export, auditContext);

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + export.filename() + "\"");
        headers.add("X-AccessFlow-Export-Truncated", Boolean.toString(export.truncated()));
        return ResponseEntity.ok().headers(headers).body(export.content());
    }

    private static GrantUsageReportQuery query(GrantResourceKind resourceKind,
                                               List<GrantUsageRecommendation> recommendations,
                                               UUID resourceId, UUID userId) {
        return new GrantUsageReportQuery(resourceKind,
                recommendations == null ? Set.of() : Set.copyOf(recommendations),
                resourceId, userId);
    }

    /**
     * The filters are recorded alongside the row count so a later reader can tell a small export
     * apart from a filtered one. Swallowed on failure — an audit outage must not deny an auditor the
     * report (unlike the compliance exporter, this export carries no signature to chain).
     */
    private void recordExport(JwtClaims caller, GrantUsageReportQuery filter,
                              GrantUsageExportService.UsageExport export,
                              RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("row_count", export.rowCount());
        metadata.put("truncated", export.truncated());
        if (filter.resourceKind() != null) {
            metadata.put("resource_kind", filter.resourceKind().name());
        }
        if (!filter.recommendations().isEmpty()) {
            metadata.put("recommendations",
                    filter.recommendations().stream().map(Enum::name).sorted().toList());
        }
        if (filter.resourceId() != null) {
            metadata.put("resource_id", filter.resourceId().toString());
        }
        if (filter.userId() != null) {
            metadata.put("user_id", filter.userId().toString());
        }
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.OVER_PROVISIONED_ACCESS_EXPORTED,
                    AuditResourceType.GRANT_USAGE_SUMMARY,
                    null,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext == null ? null : auditContext.ipAddress(),
                    auditContext == null ? null : auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for OVER_PROVISIONED_ACCESS_EXPORTED", ex);
        }
    }

    /**
     * A misspelled {@code resource_kind} / {@code recommendation} is a client error, not a server
     * one. Nothing maps {@code MethodArgumentTypeMismatchException} globally, so without this the
     * security module's {@code Exception} catch-all turns it into a 500. Scoped to this controller
     * deliberately — the same gap exists on other enum query params and fixing it everywhere is a
     * separate change.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleBadParameter(MethodArgumentTypeMismatchException ex) {
        var detail = messageSource.getMessage("error.invalid_request_parameter",
                new Object[]{ex.getName()}, LocaleContextHolder.getLocale());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("error", "VALIDATION_ERROR");
        problem.setProperty("timestamp", clock.instant().toString());
        return problem;
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
