package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.LangfuseConfigService;
import com.bablsoft.accessflow.ai.api.LangfuseConfigView;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/langfuse-config")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Langfuse Config", description = "Per-organization Langfuse integration settings (ADMIN only)")
@RequiredArgsConstructor
@Slf4j
class AdminLangfuseConfigController {

    private final LangfuseConfigService langfuseConfigService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Read the Langfuse configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Current configuration (secret key masked)")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    LangfuseConfigResponse get(Authentication authentication) {
        var caller = currentClaims(authentication);
        return LangfuseConfigResponse.from(langfuseConfigService.getOrDefault(caller.organizationId()));
    }

    @PutMapping
    @Operation(summary = "Update the Langfuse configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Updated configuration (secret key masked)")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    LangfuseConfigResponse update(@Valid @RequestBody UpdateLangfuseConfigRequest body,
                                  Authentication authentication,
                                  RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var before = langfuseConfigService.getOrDefault(caller.organizationId());
        var after = langfuseConfigService.update(caller.organizationId(), body.toCommand());
        recordAudit(before, after, body, caller, auditContext);
        return LangfuseConfigResponse.from(after);
    }

    @PostMapping("/test")
    @Operation(summary = "Verify the saved Langfuse credentials against the Langfuse API")
    @ApiResponse(responseCode = "200", description = "Connectivity check completed — see status")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    LangfuseConfigTestResponse test(Authentication authentication) {
        var caller = currentClaims(authentication);
        return LangfuseConfigTestResponse.from(langfuseConfigService.testConnection(caller.organizationId()));
    }

    private void recordAudit(LangfuseConfigView before, LangfuseConfigView after,
                             UpdateLangfuseConfigRequest body, JwtClaims caller,
                             RequestAuditContext auditContext) {
        var metadata = new LinkedHashMap<String, Object>();
        if (before.enabled() != after.enabled()) {
            metadata.put("enabled", after.enabled());
        }
        if (before.tracingEnabled() != after.tracingEnabled()) {
            metadata.put("tracing_enabled", after.tracingEnabled());
        }
        if (before.promptManagementEnabled() != after.promptManagementEnabled()) {
            metadata.put("prompt_management_enabled", after.promptManagementEnabled());
        }
        if (secretChanged(body, before)) {
            metadata.put("secret_key_changed", true);
        }
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.LANGFUSE_CONFIG_UPDATED,
                    AuditResourceType.LANGFUSE_CONFIG,
                    after.id(),
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for LANGFUSE_CONFIG_UPDATED in org {}", caller.organizationId(), ex);
        }
    }

    private static boolean secretChanged(UpdateLangfuseConfigRequest body, LangfuseConfigView before) {
        var submitted = body.secretKey();
        if (submitted == null
                || com.bablsoft.accessflow.ai.api.UpdateLangfuseConfigCommand.MASKED_SECRET.equals(submitted)) {
            return false;
        }
        if (submitted.isBlank()) {
            return before.secretKeyConfigured();
        }
        return true;
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
