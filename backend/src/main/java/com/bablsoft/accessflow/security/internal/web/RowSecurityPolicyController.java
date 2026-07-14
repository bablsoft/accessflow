package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.CreateRowSecurityPolicyCommand;
import com.bablsoft.accessflow.core.api.RowSecurityPolicyAdminService;
import com.bablsoft.accessflow.core.api.RowSecurityPolicyView;
import com.bablsoft.accessflow.core.api.UpdateRowSecurityPolicyCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.CreateRowSecurityPolicyRequest;
import com.bablsoft.accessflow.security.internal.web.model.RowSecurityPolicyListResponse;
import com.bablsoft.accessflow.security.internal.web.model.RowSecurityPolicyResponse;
import com.bablsoft.accessflow.security.internal.web.model.UpdateRowSecurityPolicyRequest;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/datasources/{datasourceId}/row-security-policies")
@Tag(name = "Row security policies",
        description = "Per-table row-level security predicates injected into queries at execution")
@PreAuthorize("hasAuthority('PERM_ROW_SECURITY_MANAGE')")
@RequiredArgsConstructor
@Slf4j
class RowSecurityPolicyController {

    private final RowSecurityPolicyAdminService rowSecurityPolicyAdminService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List row-security policies configured on a datasource")
    @ApiResponse(responseCode = "200", description = "List of row-security policies")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    RowSecurityPolicyListResponse list(@PathVariable UUID datasourceId,
                                       Authentication authentication) {
        var caller = currentClaims(authentication);
        var policies = rowSecurityPolicyAdminService
                .listForDatasource(datasourceId, caller.organizationId()).stream()
                .map(RowSecurityPolicyResponse::from)
                .toList();
        return new RowSecurityPolicyListResponse(policies);
    }

    @PostMapping
    @Operation(summary = "Create a row-security policy on a datasource table")
    @ApiResponse(responseCode = "201", description = "Row-security policy created")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    @ApiResponse(responseCode = "422", description = "Invalid predicate, variable, or applies-to target")
    ResponseEntity<RowSecurityPolicyResponse> create(
            @PathVariable UUID datasourceId,
            @Valid @RequestBody CreateRowSecurityPolicyRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new CreateRowSecurityPolicyCommand(
                request.tableName(),
                request.columnName(),
                request.operator(),
                request.valueType(),
                request.valueExpression(),
                request.appliesToRoles(),
                request.appliesToGroupIds(),
                request.appliesToUserIds(),
                request.enabled());
        var view = rowSecurityPolicyAdminService.create(datasourceId, caller.organizationId(),
                command);
        recordAudit(AuditAction.ROW_SECURITY_POLICY_CREATED, view, caller, auditContext);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{policyId}")
                .buildAndExpand(view.id())
                .toUri();
        return ResponseEntity.created(location).body(RowSecurityPolicyResponse.from(view));
    }

    @PutMapping("/{policyId}")
    @Operation(summary = "Update a row-security policy")
    @ApiResponse(responseCode = "200", description = "Row-security policy updated")
    @ApiResponse(responseCode = "404", description = "Datasource or policy not found")
    @ApiResponse(responseCode = "422", description = "Invalid predicate, variable, or applies-to target")
    RowSecurityPolicyResponse update(
            @PathVariable UUID datasourceId,
            @PathVariable UUID policyId,
            @Valid @RequestBody UpdateRowSecurityPolicyRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new UpdateRowSecurityPolicyCommand(
                request.tableName(),
                request.columnName(),
                request.operator(),
                request.valueType(),
                request.valueExpression(),
                request.appliesToRoles(),
                request.appliesToGroupIds(),
                request.appliesToUserIds(),
                request.enabled());
        var view = rowSecurityPolicyAdminService.update(policyId, datasourceId,
                caller.organizationId(), command);
        recordAudit(AuditAction.ROW_SECURITY_POLICY_UPDATED, view, caller, auditContext);
        return RowSecurityPolicyResponse.from(view);
    }

    @DeleteMapping("/{policyId}")
    @Operation(summary = "Delete a row-security policy")
    @ApiResponse(responseCode = "204", description = "Row-security policy deleted")
    @ApiResponse(responseCode = "404", description = "Datasource or policy not found")
    ResponseEntity<Void> delete(@PathVariable UUID datasourceId,
                                @PathVariable UUID policyId,
                                Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        rowSecurityPolicyAdminService.delete(policyId, datasourceId, caller.organizationId());
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", datasourceId.toString());
        recordAudit(AuditAction.ROW_SECURITY_POLICY_DELETED, AuditResourceType.ROW_SECURITY_POLICY,
                policyId, caller, auditContext, metadata);
        return ResponseEntity.noContent().build();
    }

    private void recordAudit(AuditAction action, RowSecurityPolicyView view, JwtClaims caller,
                             RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", view.datasourceId().toString());
        metadata.put("table_name", view.tableName());
        metadata.put("column_name", view.columnName());
        metadata.put("operator", view.operator().name());
        metadata.put("enabled", view.enabled());
        recordAudit(action, AuditResourceType.ROW_SECURITY_POLICY, view.id(), caller, auditContext,
                metadata);
    }

    private void recordAudit(AuditAction action, AuditResourceType resourceType, UUID resourceId,
                             JwtClaims caller, RequestAuditContext auditContext,
                             Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    resourceType,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {} {}", action, resourceType.dbValue(),
                    resourceId, ex);
        }
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
