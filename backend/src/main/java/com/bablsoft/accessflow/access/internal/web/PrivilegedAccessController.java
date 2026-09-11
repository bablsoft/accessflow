package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.PrivilegedAccessQuery;
import com.bablsoft.accessflow.access.api.PrivilegedAccessService;
import com.bablsoft.accessflow.access.api.StandingBypassKind;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
import java.util.UUID;

/**
 * The standing privileged-access report (#968) — every identity that can reach data without
 * appearing in any permission table, for admins and auditors.
 *
 * <p>Read-only and advisory: nothing here revokes anything. The same gate as the effective-access
 * explainer, so auditors who read grants at {@code /admin/over-provisioned-access} can read the
 * bypasses those grants cannot show.
 */
@RestController
@RequestMapping("/api/v1/admin/privileged-access")
@PreAuthorize("hasAnyAuthority('PERM_DATASOURCE_PERMISSION_MANAGE','PERM_ACCESS_USAGE_REPORT_VIEW')")
@Tag(name = "Privileged Access",
        description = "QUERY_ADMIN holders and break-glass grantees — access with no permission row "
                + "(ADMIN / AUDITOR only)")
@RequiredArgsConstructor
@Slf4j
class PrivilegedAccessController {

    private final PrivilegedAccessService privilegedAccessService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;
    private final Clock clock;

    @GetMapping
    @Operation(summary = "List every identity that can reach data without a permission row — "
            + "QUERY_ADMIN holders and break-glass grantees — with their query evidence")
    @ApiResponse(responseCode = "200", description = "Page of identities, one row each")
    @ApiResponse(responseCode = "400", description = "Unparseable kind or user_id")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin or auditor")
    PrivilegedAccessPageResponse list(
            @RequestParam(name = "kind", required = false) StandingBypassKind kind,
            @RequestParam(name = "user_id", required = false) UUID userId,
            Authentication authentication, Pageable pageable, RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var query = new PrivilegedAccessQuery(kind, userId);
        var page = privilegedAccessService.report(caller.organizationId(), query,
                SpringPageableAdapter.toPageRequest(pageable));
        recordRead(caller, query, page.totalElements(), auditContext);
        return PrivilegedAccessPageResponse.from(page);
    }

    /**
     * Read-only, but it discloses who can bypass the permission gate, so every read is audited
     * like the other sensitive reads. The filters travel with the total so a later reader can tell
     * a narrowed read from a full one; no emails or role names are recorded. Swallowed on failure —
     * an audit outage must not deny an auditor the report.
     */
    private void recordRead(JwtClaims caller, PrivilegedAccessQuery query, long rowCount,
                            RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("row_count", rowCount);
        if (query.kind() != null) {
            metadata.put("kind", query.kind().name());
        }
        if (query.userId() != null) {
            metadata.put("user_id", query.userId().toString());
        }
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.PRIVILEGED_ACCESS_REPORT_VIEWED,
                    AuditResourceType.ORGANIZATION,
                    caller.organizationId(),
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext == null ? null : auditContext.ipAddress(),
                    auditContext == null ? null : auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for PRIVILEGED_ACCESS_REPORT_VIEWED", ex);
        }
    }

    /**
     * A misspelled {@code kind} or a non-UUID {@code user_id} is a client error, not a server one.
     * Nothing maps {@code MethodArgumentTypeMismatchException} globally, so without this the
     * security module's {@code Exception} catch-all turns it into a 500 (same local handler as the
     * over-provisioned report).
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
