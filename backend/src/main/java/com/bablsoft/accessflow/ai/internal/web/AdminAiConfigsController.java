package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.api.AiConfigService;
import com.bablsoft.accessflow.ai.api.AiConfigView;
import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SupportedLanguage;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ai-configs")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin AI Configs", description = "Per-organization AI analyzer configurations (ADMIN only)")
@RequiredArgsConstructor
@Slf4j
class AdminAiConfigsController {

    private static final String TEST_SQL = "SELECT 1";

    private final AiConfigService aiConfigService;
    private final AiAnalyzerStrategy aiAnalyzerStrategy;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List AI analyzer configurations for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Configurations (API keys masked)")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    List<AiConfigResponse> list(Authentication authentication) {
        var caller = currentClaims(authentication);
        return aiConfigService.list(caller.organizationId()).stream()
                .map(AiConfigResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read a single AI configuration")
    @ApiResponse(responseCode = "200", description = "Configuration (API key masked)")
    @ApiResponse(responseCode = "404", description = "Configuration not found in this organization")
    AiConfigResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return AiConfigResponse.from(aiConfigService.get(id, caller.organizationId()));
    }

    @PostMapping
    @Operation(summary = "Create a new AI configuration for the caller's organization")
    @ApiResponse(responseCode = "201", description = "Configuration created (API key masked)")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "An AI configuration with that name already exists")
    ResponseEntity<AiConfigResponse> create(@Valid @RequestBody CreateAiConfigRequest body,
                                            Authentication authentication,
                                            HttpServletRequest httpRequest) {
        var caller = currentClaims(authentication);
        var created = aiConfigService.create(caller.organizationId(), body.toCommand());
        recordAudit(AuditAction.AI_CONFIG_CREATED, created.id(), caller, httpRequest,
                Map.of("name", created.name(), "provider", created.provider().name(),
                        "model", created.model()));
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(AiConfigResponse.from(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an AI configuration. Triggers a runtime refresh of the active "
            + "delegate and emits an AI_CONFIG_UPDATED audit row when provider/model/key change.")
    @ApiResponse(responseCode = "200", description = "Updated configuration (API key masked)")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Configuration not found in this organization")
    @ApiResponse(responseCode = "409", description = "An AI configuration with that name already exists")
    AiConfigResponse update(@PathVariable UUID id,
                            @Valid @RequestBody UpdateAiConfigRequest body,
                            Authentication authentication,
                            HttpServletRequest httpRequest) {
        var caller = currentClaims(authentication);
        var before = aiConfigService.get(id, caller.organizationId());
        var after = aiConfigService.update(id, caller.organizationId(), body.toCommand());
        recordAuditIfChanged(before, after, body, caller, httpRequest);
        return AiConfigResponse.from(after);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an AI configuration. Rejected with 409 when bound to one or more datasources.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Configuration not found in this organization")
    @ApiResponse(responseCode = "409", description = "Configuration is still bound to one or more datasources")
    ResponseEntity<Void> delete(@PathVariable UUID id,
                                Authentication authentication,
                                HttpServletRequest httpRequest) {
        var caller = currentClaims(authentication);
        aiConfigService.delete(id, caller.organizationId());
        recordAudit(AuditAction.AI_CONFIG_DELETED, id, caller, httpRequest, Map.of());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Send a synthetic prompt through the named AI configuration and report back")
    @ApiResponse(responseCode = "200", description = "Provider responded — see status")
    @ApiResponse(responseCode = "404", description = "Configuration not found in this organization")
    TestAiConfigResponse test(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        var view = aiConfigService.get(id, caller.organizationId());
        try {
            var result = aiAnalyzerStrategy.analyze(TEST_SQL, DbType.POSTGRESQL, null,
                    SupportedLanguage.EN.code(), view.id());
            return TestAiConfigResponse.ok("AI provider responded with risk_level=" + result.riskLevel());
        } catch (RuntimeException ex) {
            log.warn("AI test prompt failed for ai_config={}", id, ex);
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
        if (!Objects.equals(before.name(), after.name())) {
            metadata.put("old_name", before.name());
            metadata.put("new_name", after.name());
        }
        if (apiKeyChanged(body, before)) {
            metadata.put("api_key_changed", true);
        }
        if (metadata.isEmpty()) {
            return;
        }
        recordAudit(AuditAction.AI_CONFIG_UPDATED, after.id(), caller, httpRequest, metadata);
    }

    private boolean apiKeyChanged(UpdateAiConfigRequest body, AiConfigView before) {
        var submitted = body.apiKey();
        if (submitted == null || UpdateAiConfigCommand.MASKED_API_KEY.equals(submitted)) {
            return false;
        }
        if (submitted.isBlank()) {
            return before.apiKeyMasked();
        }
        return true;
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             HttpServletRequest httpRequest, Map<String, Object> metadata) {
        try {
            var context = RequestAuditContext.from(httpRequest);
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.AI_CONFIG,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on ai_config {}", action, resourceId, ex);
        }
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
