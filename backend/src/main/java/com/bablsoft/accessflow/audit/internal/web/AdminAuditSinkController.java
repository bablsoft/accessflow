package com.bablsoft.accessflow.audit.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.AuditSinkService;
import com.bablsoft.accessflow.audit.api.CreateAuditSinkCommand;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.audit.api.UpdateAuditSinkCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit-sinks")
@PreAuthorize("hasAuthority('PERM_AUDIT_SINK_MANAGE')")
@Tag(name = "Audit Sinks",
        description = "Admin management of external audit-log sinks: Splunk HEC, syslog/CEF, "
                + "HMAC-signed HTTPS, and S3 Object Lock (WORM) segment archival")
@RequiredArgsConstructor
@Slf4j
class AdminAuditSinkController {

    private final AuditSinkService service;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List audit sinks for the caller's organization, with delivery health")
    @ApiResponse(responseCode = "200", description = "Sinks with sensitive config fields masked")
    @ApiResponse(responseCode = "403", description = "Caller lacks AUDIT_SINK_MANAGE")
    List<AuditSinkResponse> list(
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        return service.list(organizationId).stream()
                .map(AuditSinkResponse::from)
                .toList();
    }

    @PostMapping
    @Operation(summary = "Create a new audit sink")
    @ApiResponse(responseCode = "201", description = "Sink created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller lacks AUDIT_SINK_MANAGE")
    @ApiResponse(responseCode = "409", description = "Sink name already exists in the organization")
    @ApiResponse(responseCode = "422", description = "Sink config is missing required keys for its type")
    ResponseEntity<AuditSinkResponse> create(
            @Valid @RequestBody CreateAuditSinkRequest body,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID callerUserId,
            RequestAuditContext auditContext) {
        var view = service.create(new CreateAuditSinkCommand(
                organizationId, body.type(), body.name(), body.config()));
        recordAudit(AuditAction.AUDIT_SINK_CREATED, view.id(), view.name(),
                view.type().name(), organizationId, callerUserId, auditContext);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(view.id())
                .toUri();
        return ResponseEntity.created(location).body(AuditSinkResponse.from(view));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an audit sink's name, config, or enabled flag")
    @ApiResponse(responseCode = "200", description = "Sink updated")
    @ApiResponse(responseCode = "403", description = "Caller lacks AUDIT_SINK_MANAGE")
    @ApiResponse(responseCode = "404", description = "Sink not found in caller's organization")
    @ApiResponse(responseCode = "409", description = "Sink name already exists in the organization")
    @ApiResponse(responseCode = "422", description = "Sink config is missing required keys for its type")
    AuditSinkResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAuditSinkRequest body,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID callerUserId,
            RequestAuditContext auditContext) {
        var view = service.update(id, organizationId,
                new UpdateAuditSinkCommand(body.name(), body.config(), body.enabled()));
        recordAudit(AuditAction.AUDIT_SINK_UPDATED, view.id(), view.name(),
                view.type().name(), organizationId, callerUserId, auditContext);
        return AuditSinkResponse.from(view);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an audit sink")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "403", description = "Caller lacks AUDIT_SINK_MANAGE")
    @ApiResponse(responseCode = "404", description = "Sink not found in caller's organization")
    ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID callerUserId,
            RequestAuditContext auditContext) {
        service.delete(id, organizationId);
        recordAudit(AuditAction.AUDIT_SINK_DELETED, id, null, null,
                organizationId, callerUserId, auditContext);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Deliver a synthetic test event through the sink")
    @ApiResponse(responseCode = "200", description = "Test delivery succeeded")
    @ApiResponse(responseCode = "403", description = "Caller lacks AUDIT_SINK_MANAGE")
    @ApiResponse(responseCode = "404", description = "Sink not found in caller's organization")
    @ApiResponse(responseCode = "502", description = "Test delivery failed")
    TestAuditSinkResponse sendTest(
            @PathVariable UUID id,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        service.sendTest(id, organizationId);
        return TestAuditSinkResponse.ok("Test event delivered");
    }

    private void recordAudit(AuditAction action, UUID sinkId, String name, String type,
                             UUID organizationId, UUID callerUserId,
                             RequestAuditContext auditContext) {
        try {
            var metadata = name == null
                    ? Map.<String, Object>of()
                    : Map.<String, Object>of("name", name, "type", type);
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.AUDIT_SINK,
                    sinkId,
                    organizationId,
                    callerUserId,
                    metadata,
                    auditContext == null ? null : auditContext.ipAddress(),
                    auditContext == null ? null : auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on audit_sink {}", action, sinkId, ex);
        }
    }
}
