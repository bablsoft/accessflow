package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.EffectiveAccessQuery;
import com.bablsoft.accessflow.workflow.api.EffectiveAccessService;
import com.bablsoft.accessflow.workflow.api.InvalidEffectiveAccessQueryException;
import com.bablsoft.accessflow.workflow.api.StatementCapability;
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
 * The access explainer's reverse index (AF-859): who could submit a statement class against one
 * table, and from which grant.
 *
 * <p>Also open to {@code ACCESS_USAGE_REPORT_VIEW} so auditors — who already read this class of data
 * on the over-provisioned access report (#625) — can use it read-only.
 */
@RestController
@RequestMapping("/api/v1/admin/effective-access")
@PreAuthorize("hasAnyAuthority('PERM_DATASOURCE_PERMISSION_MANAGE','PERM_ACCESS_USAGE_REPORT_VIEW')")
@Tag(name = "Access Explainer",
        description = "Who can reach a table, and why (ADMIN / AUDITOR only)")
@RequiredArgsConstructor
@Slf4j
class AdminEffectiveAccessController {

    private final EffectiveAccessService effectiveAccessService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;
    private final Clock clock;

    @GetMapping
    @Operation(summary = "List every user who could submit this statement class against this table",
            description = "One row per user, naming every source that grants it — the direct row, "
                    + "each inherited group permission, an active JIT grant, a QUERY_ADMIN bypass "
                    + "and break-glass. Answers who may submit, not what would happen: for that, "
                    + "follow up with an access simulation for one user.")
    @ApiResponse(responseCode = "200", description = "Page of users who can reach the table")
    @ApiResponse(responseCode = "400", description = "Missing or unusable query parameter")
    @ApiResponse(responseCode = "403", description = "Caller is neither an admin nor an auditor")
    @ApiResponse(responseCode = "404",
            description = "Datasource missing or in another organization")
    EffectiveAccessPageResponse report(
            @RequestParam(name = "datasource_id") UUID datasourceId,
            @RequestParam(name = "table") String table,
            @RequestParam(name = "capability") StatementCapability capability,
            Authentication authentication, Pageable pageable,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var query = new EffectiveAccessQuery(datasourceId, table, capability);
        var page = effectiveAccessService.report(caller.organizationId(), query,
                SpringPageableAdapter.toPageRequest(pageable));
        recordRead(caller, query, page.totalElements(), auditContext);
        return EffectiveAccessPageResponse.from(page);
    }

    /**
     * Audited like the simulator and for the same reason: read-only, but it discloses the
     * organization's access topology. Swallowed on failure so an audit outage cannot deny an auditor
     * the report.
     */
    private void recordRead(JwtClaims caller, EffectiveAccessQuery query, long rowCount,
                            RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("table", query.table());
        metadata.put("capability", query.capability().name());
        metadata.put("row_count", rowCount);
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.ACCESS_SIMULATION_RUN,
                    AuditResourceType.DATASOURCE,
                    query.datasourceId(),
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext == null ? null : auditContext.ipAddress(),
                    auditContext == null ? null : auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for ACCESS_SIMULATION_RUN", ex);
        }
    }

    /** A table that normalizes away to nothing is a client error, not an empty result. */
    @ExceptionHandler(InvalidEffectiveAccessQueryException.class)
    ProblemDetail handleInvalidQuery(InvalidEffectiveAccessQueryException ex) {
        var detail = messageSource.getMessage("error.effective_access_table_invalid", null,
                LocaleContextHolder.getLocale());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("error", "VALIDATION_ERROR");
        problem.setProperty("timestamp", clock.instant().toString());
        return problem;
    }

    /**
     * A misspelled {@code capability} is a client error. Nothing maps
     * {@code MethodArgumentTypeMismatchException} globally, so without this the security module's
     * {@code Exception} catch-all turns it into a 500.
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
