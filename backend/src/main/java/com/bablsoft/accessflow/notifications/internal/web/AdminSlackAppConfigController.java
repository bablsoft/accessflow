package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.notifications.api.SlackAppConfigNotFoundException;
import com.bablsoft.accessflow.notifications.api.SlackAppConfigService;
import com.bablsoft.accessflow.notifications.internal.DefaultSlackAppConfigService;
import com.bablsoft.accessflow.notifications.internal.SlackMessages;
import com.bablsoft.accessflow.notifications.internal.strategy.SlackBotMessenger;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Per-organization Slack app configuration (ADMIN only). Holds the bot token + signing secret
 * (encrypted), the Slack app id used to route inbound callbacks, and the default channel for
 * outbound interactive review-request messages.
 */
@RestController
@RequestMapping("/api/v1/admin/slack-app-config")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Slack App Config", description = "Per-organization Slack app settings (ADMIN only)")
@RequiredArgsConstructor
@Slf4j
class AdminSlackAppConfigController {

    private final SlackAppConfigService slackAppConfigService;
    private final DefaultSlackAppConfigService slackAppRuntime;
    private final SlackBotMessenger botMessenger;
    private final SlackMessages messages;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "Read the Slack app configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Configuration (secrets masked)")
    @ApiResponse(responseCode = "404", description = "No Slack app configured")
    ResponseEntity<SlackAppConfigResponse> get(Authentication authentication) {
        var caller = currentClaims(authentication);
        return slackAppConfigService.get(caller.organizationId())
                .map(SlackAppConfigResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping
    @Operation(summary = "Create or update the Slack app configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Saved configuration (secrets masked)")
    @ApiResponse(responseCode = "422", description = "Missing required fields (e.g. bot token on create)")
    SlackAppConfigResponse upsert(@Valid @RequestBody UpsertSlackAppConfigRequest body,
                                  Authentication authentication,
                                  RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var saved = slackAppConfigService.upsert(caller.organizationId(), body.toCommand());
        recordAudit(AuditAction.SLACK_APP_CONFIG_UPDATED, saved.id(), caller, auditContext,
                Map.of("app_id", saved.appId(), "active", saved.active()));
        return SlackAppConfigResponse.from(saved);
    }

    @DeleteMapping
    @Operation(summary = "Delete the Slack app configuration for the caller's organization")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No Slack app configured")
    ResponseEntity<Void> delete(Authentication authentication, RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        slackAppConfigService.delete(caller.organizationId());
        recordAudit(AuditAction.SLACK_APP_CONFIG_DELETED, null, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    @Operation(summary = "Post a test message to the configured default channel via the bot token")
    @ApiResponse(responseCode = "200", description = "Slack responded — see status")
    @ApiResponse(responseCode = "404", description = "No Slack app configured")
    TestSlackResponse test(Authentication authentication) {
        var caller = currentClaims(authentication);
        var app = slackAppRuntime.findDecryptedByOrg(caller.organizationId())
                .orElseThrow(() -> new SlackAppConfigNotFoundException(caller.organizationId()));
        try {
            botMessenger.postMessage(app.botToken(), app.defaultChannelId(),
                    messages.forOrg(caller.organizationId(), "slack.test.message"), null);
            return TestSlackResponse.ok(messages.forOrg(caller.organizationId(), "slack.test.message"));
        } catch (RuntimeException ex) {
            log.warn("Slack test message failed for org {}", caller.organizationId(), ex);
            return TestSlackResponse.error(ex.getMessage());
        }
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.SLACK_APP_CONFIG,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on slack_app_config", action, ex);
        }
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
