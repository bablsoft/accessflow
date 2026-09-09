package com.bablsoft.accessflow.api.internal.web;

import com.bablsoft.accessflow.api.internal.GovernanceDomainsService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The organization's own governance-domain switches (#926) — the in-app way back for an admin who
 * declined a domain in the first-run wizard, which before this needed a platform admin on
 * {@code /admin/organizations/:id}. Scoped to the caller's organization from the JWT: there is no
 * id in the path, so this can never edit another tenant. Gated on {@code SETUP_PROGRESS_VIEW},
 * the permission that already exposes both flags through {@code GET /admin/setup-progress} — so
 * that permission authorizes this write too, which its Javadoc and role-catalog label say.
 *
 * <p>The write is audited as {@code ORGANIZATION_UPDATED}, exactly as the platform-admin path
 * ({@code PUT /platform/organizations/{id}}) audits the same column write — the two surfaces
 * mutate the same row, so they must leave the same trail.
 */
@RestController
@RequestMapping("/api/v1/admin/governance-domains")
@PreAuthorize("hasAuthority('PERM_SETUP_PROGRESS_VIEW')")
@Tag(name = "Admin Governance Domains",
        description = "Which governance domains the caller's organization uses (ADMIN only)")
@RequiredArgsConstructor
@Slf4j
class AdminGovernanceDomainsController {

    private final GovernanceDomainsService governanceDomainsService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Return the governance domains the caller's organization has opted into")
    @ApiResponse(responseCode = "200", description = "Current domain flags")
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    GovernanceDomainsResponse get(Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        return GovernanceDomainsResponse.from(governanceDomainsService.get(caller.organizationId()));
    }

    @PutMapping
    @Operation(summary = "Replace the governance domains the caller's organization has opted into")
    @ApiResponse(responseCode = "200", description = "Updated domain flags")
    @ApiResponse(responseCode = "400", description = "Validation error (a flag is missing)")
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    GovernanceDomainsResponse update(Authentication authentication,
                                     @Valid @RequestBody UpdateGovernanceDomainsRequest request,
                                     RequestAuditContext auditContext) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var updated = governanceDomainsService.update(
                caller.organizationId(), request.governsApis(), request.governsDeployments());
        recordAudit(caller, auditContext, Map.of(
                "governs_apis", updated.governsApis(),
                "governs_deployments", updated.governsDeployments()));
        return GovernanceDomainsResponse.from(updated);
    }

    private void recordAudit(JwtClaims caller, RequestAuditContext auditContext,
                             Map<String, Object> metadata) {
        UUID organizationId = caller.organizationId();
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.ORGANIZATION_UPDATED,
                    AuditResourceType.ORGANIZATION,
                    organizationId,
                    organizationId,
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on organization {}",
                    AuditAction.ORGANIZATION_UPDATED, organizationId, ex);
        }
    }
}
