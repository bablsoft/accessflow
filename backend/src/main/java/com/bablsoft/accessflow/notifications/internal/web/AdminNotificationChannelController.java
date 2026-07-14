package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.notifications.api.CreateNotificationChannelCommand;
import com.bablsoft.accessflow.notifications.api.NotificationChannelService;
import com.bablsoft.accessflow.notifications.api.UpdateNotificationChannelCommand;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/notification-channels")
@PreAuthorize("hasAuthority('PERM_NOTIFICATION_CHANNEL_MANAGE')")
@Tag(name = "Notification Channels", description = "Admin management of email, Slack, webhook, Discord, Telegram, and Microsoft Teams notification channels")
@RequiredArgsConstructor
@Slf4j
class AdminNotificationChannelController {

    private final NotificationChannelService service;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List notification channels for the caller's organization")
    @ApiResponse(responseCode = "200", description = "List of channels with sensitive fields masked")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    List<NotificationChannelResponse> list(Authentication authentication) {
        var caller = currentClaims(authentication);
        return service.list(caller.organizationId()).stream()
                .map(NotificationChannelResponse::from)
                .toList();
    }

    @PostMapping
    @Operation(summary = "Create a new notification channel")
    @ApiResponse(responseCode = "201", description = "Channel created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @ApiResponse(responseCode = "422", description = "Channel config is missing required keys")
    ResponseEntity<NotificationChannelResponse> create(
            @Valid @RequestBody CreateNotificationChannelRequest body,
            Authentication authentication) {
        var caller = currentClaims(authentication);
        var command = new CreateNotificationChannelCommand(
                caller.organizationId(),
                body.channelType(),
                body.name(),
                body.config());
        var view = service.create(command);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(view.id())
                .toUri();
        return ResponseEntity.created(location).body(NotificationChannelResponse.from(view));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a notification channel's config, name, or active flag")
    @ApiResponse(responseCode = "200", description = "Channel updated")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @ApiResponse(responseCode = "404", description = "Channel not found in caller's organization")
    @ApiResponse(responseCode = "422", description = "Channel config is missing required keys")
    NotificationChannelResponse update(@PathVariable UUID id,
                                       @Valid @RequestBody UpdateNotificationChannelRequest body,
                                       Authentication authentication) {
        var caller = currentClaims(authentication);
        var command = new UpdateNotificationChannelCommand(
                body.name(), body.config(), body.active());
        var view = service.update(id, caller.organizationId(), command);
        return NotificationChannelResponse.from(view);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a notification channel")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @ApiResponse(responseCode = "404", description = "Channel not found in caller's organization")
    ResponseEntity<Void> delete(@PathVariable UUID id,
                                Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        service.delete(id, caller.organizationId());
        recordAudit(AuditAction.NOTIFICATION_CHANNEL_DELETED, id, caller, auditContext);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Send a test message via the configured channel")
    @ApiResponse(responseCode = "200", description = "Test delivery succeeded")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @ApiResponse(responseCode = "404", description = "Channel not found in caller's organization")
    @ApiResponse(responseCode = "502", description = "Test delivery failed")
    TestNotificationResponse sendTest(@PathVariable UUID id,
                                      @Valid @RequestBody(required = false)
                                      TestNotificationChannelRequest body,
                                      Authentication authentication) {
        var caller = currentClaims(authentication);
        var emailOverride = body != null ? body.email() : null;
        service.sendTest(id, caller.organizationId(), emailOverride);
        return TestNotificationResponse.ok("Test notification dispatched");
    }

    private void recordAudit(AuditAction action, UUID channelId, JwtClaims caller,
                             RequestAuditContext auditContext) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.NOTIFICATION_CHANNEL,
                    channelId,
                    caller.organizationId(),
                    caller.userId(),
                    Map.of(),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on notification_channel {}", action, channelId, ex);
        }
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
