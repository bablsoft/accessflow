package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.EffectivePermissionExplorerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * The effective-permission explorer (#946): what one user can do on one datasource, and why.
 *
 * <p>Unlike the reverse index on {@code /admin/effective-access}, not open to
 * {@code ACCESS_USAGE_REPORT_VIEW}: this read discloses masking and row-security configuration.
 */
@RestController
@RequestMapping("/api/v1/admin/effective-access/users/{userId}/datasources/{datasourceId}")
@PreAuthorize("hasAuthority('PERM_DATASOURCE_PERMISSION_MANAGE')")
@Tag(name = "Access Explainer")
@RequiredArgsConstructor
@Slf4j
class AdminEffectivePermissionController {

    private final EffectivePermissionExplorerService explorerService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Explain one user's effective access on one datasource",
            description = "Merged capabilities, table scope, denied objects, masked columns, "
                    + "row-security predicates and the effective row cap, each with the grant, "
                    + "group or policy it came from and its expiry. Read-only; assembled from the "
                    + "same services enforcement uses.")
    @ApiResponse(responseCode = "200", description = "Effective access")
    @ApiResponse(responseCode = "403", description = "Caller cannot manage datasource permissions")
    @ApiResponse(responseCode = "404",
            description = "User or datasource missing, or in another organization")
    EffectivePermissionResponse explain(@PathVariable UUID userId, @PathVariable UUID datasourceId,
                                        Authentication authentication,
                                        RequestAuditContext auditContext) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var explanation = explorerService.explain(caller.organizationId(), userId, datasourceId);
        recordRead(caller, userId, datasourceId, auditContext);
        return EffectivePermissionResponse.from(explanation);
    }

    /** Swallowed on failure, like the other audited reads: an audit outage must not deny the view. */
    private void recordRead(JwtClaims caller, UUID userId, UUID datasourceId,
                            RequestAuditContext auditContext) {
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.EFFECTIVE_PERMISSION_VIEWED,
                    AuditResourceType.DATASOURCE,
                    datasourceId,
                    caller.organizationId(),
                    caller.userId(),
                    Map.of("target_user_id", userId.toString()),
                    auditContext == null ? null : auditContext.ipAddress(),
                    auditContext == null ? null : auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for EFFECTIVE_PERMISSION_VIEWED", ex);
        }
    }
}
