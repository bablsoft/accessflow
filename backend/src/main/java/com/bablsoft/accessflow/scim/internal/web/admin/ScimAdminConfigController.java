package com.bablsoft.accessflow.scim.internal.web.admin;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.scim.api.ScimConfigService;
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

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/scim-config")
@PreAuthorize("hasAuthority('PERM_SSO_CONFIGURE')")
@Tag(name = "Admin SCIM Config", description = "SCIM 2.0 provisioning settings (#621)")
@RequiredArgsConstructor
@Slf4j
class ScimAdminConfigController {

    private final ScimConfigService configService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Get the organization's SCIM provisioning configuration")
    @ApiResponse(responseCode = "200", description = "The configuration (defaults when unset)")
    @ApiResponse(responseCode = "403", description = "Caller lacks SSO_CONFIGURE")
    ScimConfigResponse getConfig(Authentication authentication) {
        var caller = currentClaims(authentication);
        return ScimConfigResponse.from(configService.get(caller.organizationId()));
    }

    @PutMapping
    @Operation(summary = "Update the organization's SCIM provisioning configuration")
    @ApiResponse(responseCode = "200", description = "Updated configuration")
    @ApiResponse(responseCode = "400", description = "Validation error")
    ScimConfigResponse updateConfig(@Valid @RequestBody UpdateScimConfigRequest request,
                                    Authentication authentication,
                                    RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var updated = configService.update(caller.organizationId(), request.toCommand());
        recordAudit(caller, auditContext, Map.of("enabled", updated.enabled()));
        return ScimConfigResponse.from(updated);
    }

    private void recordAudit(JwtClaims caller, RequestAuditContext auditContext,
                             Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.SCIM_CONFIG_UPDATED,
                    AuditResourceType.SCIM_CONFIG,
                    null,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for SCIM_CONFIG_UPDATED", ex);
        }
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
