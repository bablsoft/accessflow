package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.HelpAgentConfigService;
import com.bablsoft.accessflow.ai.api.HelpAgentConfigView;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/admin/help-agent")
@PreAuthorize("hasAuthority('PERM_AI_MANAGE')")
@Tag(name = "Admin Help Agent Config",
        description = "Per-organization settings for the in-app documentation help chat agent (ADMIN only)")
@RequiredArgsConstructor
@Slf4j
class AdminHelpAgentConfigController {

    private final HelpAgentConfigService helpAgentConfigService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Read the help-agent configuration for the caller's organization")
    @ApiResponse(responseCode = "200",
            description = "Current configuration, or the defaults when none has been saved")
    @ApiResponse(responseCode = "403", description = "Caller lacks AI_MANAGE")
    HelpAgentConfigResponse get(Authentication authentication) {
        var caller = currentClaims(authentication);
        return HelpAgentConfigResponse.from(
                helpAgentConfigService.getOrDefault(caller.organizationId()));
    }

    @PutMapping
    @Operation(summary = "Update the help-agent configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Updated configuration")
    @ApiResponse(responseCode = "400",
            description = "Validation error, or enabling a configuration that cannot answer")
    @ApiResponse(responseCode = "403", description = "Caller lacks AI_MANAGE")
    @ApiResponse(responseCode = "404", description = "Bound AI configuration not found in this organization")
    HelpAgentConfigResponse update(@Valid @RequestBody UpdateHelpAgentConfigRequest body,
                                   Authentication authentication,
                                   RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var before = helpAgentConfigService.getOrDefault(caller.organizationId());
        var after = helpAgentConfigService.update(caller.organizationId(), body.toCommand());
        recordAudit(before, after, caller, auditContext);
        return HelpAgentConfigResponse.from(after);
    }

    @PostMapping("/test")
    @Operation(summary = "Verify the bound configuration's embedding model and vector store are reachable")
    @ApiResponse(responseCode = "200", description = "Connectivity check completed — see status")
    @ApiResponse(responseCode = "403", description = "Caller lacks AI_MANAGE")
    HelpAgentTestResponse test(Authentication authentication) {
        var caller = currentClaims(authentication);
        return HelpAgentTestResponse.from(
                helpAgentConfigService.testConnection(caller.organizationId()));
    }

    @PostMapping("/reindex")
    @Operation(summary = "Request a re-ingestion of the bundled documentation corpus")
    @ApiResponse(responseCode = "202", description = "Re-index accepted")
    @ApiResponse(responseCode = "403", description = "Caller lacks AI_MANAGE")
    ResponseEntity<Void> reindex(Authentication authentication) {
        var caller = currentClaims(authentication);
        helpAgentConfigService.requestReindex(caller.organizationId());
        return ResponseEntity.accepted().build();
    }

    private void recordAudit(HelpAgentConfigView before, HelpAgentConfigView after, JwtClaims caller,
                             RequestAuditContext auditContext) {
        var metadata = new LinkedHashMap<String, Object>();
        if (before.enabled() != after.enabled()) {
            metadata.put("enabled", after.enabled());
        }
        if (!Objects.equals(before.aiConfigId(), after.aiConfigId())) {
            // Audit metadata rejects null values, so an unbind is recorded as the flag going false
            // rather than as a null id.
            metadata.put("ai_config_bound", after.aiConfigId() != null);
            if (after.aiConfigId() != null) {
                metadata.put("ai_config_id", after.aiConfigId().toString());
            }
        }
        if (before.retrievalEnabled() != after.retrievalEnabled()) {
            metadata.put("retrieval_enabled", after.retrievalEnabled());
        }
        if (before.retentionDays() != after.retentionDays()) {
            metadata.put("retention_days", after.retentionDays());
        }
        if (before.sendUserContext() != after.sendUserContext()) {
            metadata.put("send_user_context", after.sendUserContext());
        }
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.HELP_AGENT_CONFIG_UPDATED,
                    AuditResourceType.HELP_AGENT_CONFIG,
                    after.id(),
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for HELP_AGENT_CONFIG_UPDATED in org {}",
                    caller.organizationId(), ex);
        }
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
