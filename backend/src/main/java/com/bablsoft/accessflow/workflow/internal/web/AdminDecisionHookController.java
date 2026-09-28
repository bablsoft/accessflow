package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.CreateDecisionHookCommand;
import com.bablsoft.accessflow.workflow.api.DecisionHookService;
import com.bablsoft.accessflow.workflow.api.DecisionHookView;
import com.bablsoft.accessflow.workflow.api.UpdateDecisionHookCommand;
import com.bablsoft.accessflow.workflow.internal.web.model.CreateDecisionHookRequest;
import com.bablsoft.accessflow.workflow.internal.web.model.DecisionHookResponse;
import com.bablsoft.accessflow.workflow.internal.web.model.DecisionHookTestResponse;
import com.bablsoft.accessflow.workflow.internal.web.model.UpdateDecisionHookRequest;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/decision-hooks")
@PreAuthorize("hasAuthority('PERM_ROUTING_POLICY_MANAGE')")
@Tag(name = "Decision Hooks",
        description = "Admin management of the external policy decision hook consulted when no "
                + "routing policy matched (#945)")
@RequiredArgsConstructor
@Slf4j
class AdminDecisionHookController {

    private static final int DEFAULT_TIMEOUT_MS = 2000;

    private final DecisionHookService decisionHookService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List decision hooks for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Decision hooks, organization default first")
    @ApiResponse(responseCode = "403", description = "Caller lacks ROUTING_POLICY_MANAGE")
    List<DecisionHookResponse> list(Authentication authentication) {
        return decisionHookService.list(claims(authentication).organizationId()).stream()
                .map(DecisionHookResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a decision hook by id")
    @ApiResponse(responseCode = "200", description = "Decision hook")
    @ApiResponse(responseCode = "403", description = "Caller lacks ROUTING_POLICY_MANAGE")
    @ApiResponse(responseCode = "404", description = "Decision hook not found")
    DecisionHookResponse get(@PathVariable UUID id, Authentication authentication) {
        return DecisionHookResponse.from(
                decisionHookService.get(claims(authentication).organizationId(), id));
    }

    @PostMapping
    @Operation(summary = "Create a decision hook")
    @ApiResponse(responseCode = "201", description = "Decision hook created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller lacks ROUTING_POLICY_MANAGE")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    @ApiResponse(responseCode = "409", description = "The scope already has a decision hook")
    @ApiResponse(responseCode = "422", description = "Endpoint URL refused by the scheme or address check")
    ResponseEntity<DecisionHookResponse> create(@Valid @RequestBody CreateDecisionHookRequest body,
                                                Authentication authentication,
                                                RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var created = decisionHookService.create(new CreateDecisionHookCommand(
                caller.organizationId(), body.datasourceId(), body.name(), body.endpointUrl(),
                timeout(body.timeoutMs()), body.secret(), Boolean.TRUE.equals(body.includeSql()),
                body.enabled() == null || body.enabled()));
        recordAudit(AuditAction.DECISION_HOOK_CREATED, created.id(), caller, auditContext,
                metadata(created));
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(DecisionHookResponse.from(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a decision hook; an absent secret keeps the stored one")
    @ApiResponse(responseCode = "200", description = "Decision hook updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller lacks ROUTING_POLICY_MANAGE")
    @ApiResponse(responseCode = "404", description = "Decision hook or datasource not found")
    @ApiResponse(responseCode = "409", description = "The scope already has a decision hook")
    @ApiResponse(responseCode = "422", description = "Endpoint URL refused by the scheme or address check")
    DecisionHookResponse update(@PathVariable UUID id,
                                @Valid @RequestBody UpdateDecisionHookRequest body,
                                Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var updated = decisionHookService.update(caller.organizationId(), id,
                new UpdateDecisionHookCommand(body.datasourceId(), body.name(), body.endpointUrl(),
                        timeout(body.timeoutMs()), body.secret(),
                        Boolean.TRUE.equals(body.includeSql()),
                        body.enabled() == null || body.enabled()));
        var metadata = metadata(updated);
        metadata.put("secret_rotated", body.secret() != null);
        recordAudit(AuditAction.DECISION_HOOK_UPDATED, id, caller, auditContext, metadata);
        return DecisionHookResponse.from(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a decision hook")
    @ApiResponse(responseCode = "204", description = "Decision hook deleted")
    @ApiResponse(responseCode = "403", description = "Caller lacks ROUTING_POLICY_MANAGE")
    @ApiResponse(responseCode = "404", description = "Decision hook not found")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = claims(authentication);
        decisionHookService.delete(caller.organizationId(), id);
        recordAudit(AuditAction.DECISION_HOOK_DELETED, id, caller, auditContext, new HashMap<>());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Send one signed synthetic request and report the outcome",
            description = "Runs the full client — address check, timeout, signature verification, "
                    + "response parsing — without touching any query or the circuit breaker.")
    @ApiResponse(responseCode = "200", description = "Test outcome")
    @ApiResponse(responseCode = "403", description = "Caller lacks ROUTING_POLICY_MANAGE")
    @ApiResponse(responseCode = "404", description = "Decision hook not found")
    DecisionHookTestResponse test(@PathVariable UUID id, Authentication authentication,
                                  RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var result = decisionHookService.test(caller.organizationId(), id);
        var metadata = new HashMap<String, Object>();
        metadata.put("outcome", result.outcome().name());
        if (result.failure() != null) {
            metadata.put("failure", result.failure().name());
        }
        recordAudit(AuditAction.DECISION_HOOK_TESTED, id, caller, auditContext, metadata);
        return DecisionHookTestResponse.from(result);
    }

    private static int timeout(Integer timeoutMs) {
        return timeoutMs == null ? DEFAULT_TIMEOUT_MS : timeoutMs;
    }

    private static HashMap<String, Object> metadata(DecisionHookView view) {
        var metadata = new HashMap<String, Object>();
        metadata.put("name", view.name());
        metadata.put("enabled", view.enabled());
        metadata.put("include_sql", view.includeSql());
        if (view.endpointOrigin() != null) {
            metadata.put("endpoint_origin", view.endpointOrigin());
        }
        if (view.datasourceId() != null) {
            metadata.put("datasource_id", view.datasourceId().toString());
        }
        return metadata;
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(action, AuditResourceType.DECISION_HOOK,
                    resourceId, caller.organizationId(), caller.userId(), metadata,
                    auditContext.ipAddress(), auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on decision_hook {}", action, resourceId, ex);
        }
    }
}
