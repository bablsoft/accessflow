package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.CreateRoutingPolicyCommand;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyService;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyView;
import com.bablsoft.accessflow.workflow.api.UpdateRoutingPolicyCommand;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingConditionCodec;
import com.bablsoft.accessflow.workflow.internal.web.model.CreateRoutingPolicyRequest;
import com.bablsoft.accessflow.workflow.internal.web.model.ReorderRoutingPoliciesRequest;
import com.bablsoft.accessflow.workflow.internal.web.model.RoutingPolicyResponse;
import com.bablsoft.accessflow.workflow.internal.web.model.UpdateRoutingPolicyRequest;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/routing-policies")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Routing Policies",
        description = "Admin management of policy-as-code query routing rules (auto-approve, "
                + "auto-reject, require approvals, escalate)")
@RequiredArgsConstructor
@Slf4j
class AdminRoutingPolicyController {

    private final RoutingPolicyService routingPolicyService;
    private final RoutingConditionCodec routingConditionCodec;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List routing policies for the caller's organization (priority order)")
    @ApiResponse(responseCode = "200", description = "Routing policies, ascending priority")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    List<RoutingPolicyResponse> list(Authentication authentication) {
        var caller = currentClaims(authentication);
        return routingPolicyService.list(caller.organizationId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a routing policy by id")
    @ApiResponse(responseCode = "200", description = "Routing policy")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @ApiResponse(responseCode = "404", description = "Routing policy not found")
    RoutingPolicyResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return toResponse(routingPolicyService.get(id, caller.organizationId()));
    }

    @PostMapping
    @Operation(summary = "Create a routing policy")
    @ApiResponse(responseCode = "201", description = "Routing policy created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @ApiResponse(responseCode = "409", description = "Priority already in use")
    @ApiResponse(responseCode = "422", description = "Malformed condition or action parameters")
    ResponseEntity<RoutingPolicyResponse> create(
            @Valid @RequestBody CreateRoutingPolicyRequest body,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new CreateRoutingPolicyCommand(
                caller.organizationId(),
                body.datasourceId(),
                body.name(),
                body.description(),
                body.priority(),
                body.enabled() == null || body.enabled(),
                routingConditionCodec.fromJson(body.condition()),
                body.action(),
                body.requiredApprovals(),
                body.reason());
        var created = routingPolicyService.create(command);
        recordAudit(AuditAction.ROUTING_POLICY_CREATED, created.id(), caller, auditContext,
                Map.of("name", created.name(), "action", created.action().name()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(toResponse(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a routing policy")
    @ApiResponse(responseCode = "200", description = "Routing policy updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @ApiResponse(responseCode = "404", description = "Routing policy not found")
    @ApiResponse(responseCode = "409", description = "Priority already in use")
    @ApiResponse(responseCode = "422", description = "Malformed condition or action parameters")
    RoutingPolicyResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRoutingPolicyRequest body,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new UpdateRoutingPolicyCommand(
                body.datasourceId(),
                body.name(),
                body.description(),
                body.priority(),
                body.enabled() == null || body.enabled(),
                routingConditionCodec.fromJson(body.condition()),
                body.action(),
                body.requiredApprovals(),
                body.reason());
        var updated = routingPolicyService.update(id, caller.organizationId(), command);
        recordAudit(AuditAction.ROUTING_POLICY_UPDATED, id, caller, auditContext,
                Map.of("name", updated.name(), "action", updated.action().name()));
        return toResponse(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a routing policy")
    @ApiResponse(responseCode = "204", description = "Routing policy deleted")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @ApiResponse(responseCode = "404", description = "Routing policy not found")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        routingPolicyService.delete(id, caller.organizationId());
        recordAudit(AuditAction.ROUTING_POLICY_DELETED, id, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/reorder")
    @Operation(summary = "Reassign routing-policy priorities to the given order")
    @ApiResponse(responseCode = "200", description = "Reordered routing policies")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @ApiResponse(responseCode = "422", description = "Order does not match the org's policy set")
    List<RoutingPolicyResponse> reorder(
            @Valid @RequestBody ReorderRoutingPoliciesRequest body,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var reordered = routingPolicyService.reorder(caller.organizationId(), body.orderedIds());
        recordAudit(AuditAction.ROUTING_POLICY_REORDERED, caller.organizationId(), caller,
                auditContext, Map.of("count", reordered.size()));
        return reordered.stream().map(this::toResponse).toList();
    }

    private RoutingPolicyResponse toResponse(RoutingPolicyView view) {
        return RoutingPolicyResponse.from(view, routingConditionCodec.toJson(view.condition()));
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.ROUTING_POLICY,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on routing_policy {}", action, resourceId, ex);
        }
    }
}
