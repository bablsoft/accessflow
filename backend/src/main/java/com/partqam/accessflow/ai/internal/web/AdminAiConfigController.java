package com.partqam.accessflow.ai.internal.web;

import com.partqam.accessflow.ai.api.AiAnalyzerStrategy;
import com.partqam.accessflow.ai.api.AiConfigService;
import com.partqam.accessflow.ai.api.AiConfigView;
import com.partqam.accessflow.ai.api.UpdateAiConfigCommand;
import com.partqam.accessflow.audit.api.AuditAction;
import com.partqam.accessflow.audit.api.AuditEntry;
import com.partqam.accessflow.audit.api.AuditLogService;
import com.partqam.accessflow.audit.api.AuditResourceType;
import com.partqam.accessflow.audit.api.RequestAuditContext;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.SupportedLanguage;
import com.partqam.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ai-config")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin AI Config", description = "Per-organization AI analyzer settings (ADMIN only)")
@RequiredArgsConstructor
@Slf4j
class AdminAiConfigController {

    private static final String TEST_SQL = "SELECT 1";

    private final AiConfigService aiConfigService;
    private final AiAnalyzerStrategy aiAnalyzerStrategy;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Read the current AI analyzer configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Current configuration (API key masked)")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    AiConfigResponse get(Authentication authentication) {
        var caller = currentClaims(authentication);
        return AiConfigResponse.from(aiConfigService.getOrDefault(caller.organizationId()));
    }

    @PutMapping
    @Operation(summary = "Update the AI analyzer configuration for the caller's organization. "
            + "Triggers a runtime refresh of the active AiAnalyzerStrategy and emits an "
            + "AI_CONFIG_UPDATED audit row.")
    @ApiResponse(responseCode = "200", description = "Updated configuration (API key masked)")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    AiConfigResponse update(@Valid @RequestBody UpdateAiConfigRequest body,
                            Authentication authentication,
                            HttpServletRequest httpRequest) {
        var caller = currentClaims(authentication);
        var before = aiConfigService.getOrDefault(caller.organizationId());
        var after = aiConfigService.update(caller.organizationId(), body.toCommand());
        recordAuditIfChanged(before, after, body, caller, httpRequest);
        return AiConfigResponse.from(after);
    }

    @PostMapping("/test")
    @Operation(summary = "Send a synthetic prompt to the active AI provider and report back")
    @ApiResponse(responseCode = "200", description = "Provider responded — see status")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    TestAiConfigResponse test(Authentication authentication) {
        var caller = currentClaims(authentication);
        try {
            var result = aiAnalyzerStrategy.analyze(TEST_SQL, DbType.POSTGRESQL, null,
                    SupportedLanguage.EN.code(), caller.organizationId());
            return TestAiConfigResponse.ok("AI provider responded with risk_level=" + result.riskLevel());
        } catch (RuntimeException ex) {
            log.warn("AI test prompt failed", ex);
            return TestAiConfigResponse.error(ex.getMessage());
        }
    }

    private void recordAuditIfChanged(AiConfigView before, AiConfigView after,
                                      UpdateAiConfigRequest body, JwtClaims caller,
                                      HttpServletRequest httpRequest) {
        var metadata = new LinkedHashMap<String, Object>();
        if (before.provider() != after.provider()) {
            metadata.put("old_provider", before.provider().name());
            metadata.put("new_provider", after.provider().name());
        }
        if (!Objects.equals(before.model(), after.model())) {
            metadata.put("old_model", before.model());
            metadata.put("new_model", after.model());
        }
        if (apiKeyChanged(body, before, after)) {
            metadata.put("api_key_changed", true);
        }
        if (metadata.isEmpty()) {
            return;
        }
        recordAudit(after.id(), caller, httpRequest, metadata);
    }

    private boolean apiKeyChanged(UpdateAiConfigRequest body, AiConfigView before, AiConfigView after) {
        var submitted = body.apiKey();
        if (submitted == null || UpdateAiConfigCommand.MASKED_API_KEY.equals(submitted)) {
            return false;
        }
        if (submitted.isBlank()) {
            return before.apiKeyMasked();
        }
        return true;
    }

    private void recordAudit(UUID resourceId, JwtClaims caller, HttpServletRequest httpRequest,
                             Map<String, Object> metadata) {
        try {
            var context = RequestAuditContext.from(httpRequest);
            auditLogService.record(new AuditEntry(
                    AuditAction.AI_CONFIG_UPDATED,
                    AuditResourceType.AI_CONFIG,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for AI_CONFIG_UPDATED on ai_config {}", resourceId, ex);
        }
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
