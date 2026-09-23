package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.CreateRowLimitPolicyCommand;
import com.bablsoft.accessflow.core.api.RowLimitPolicyAdminService;
import com.bablsoft.accessflow.core.api.RowLimitPolicyView;
import com.bablsoft.accessflow.core.api.UpdateRowLimitPolicyCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.CreateRowLimitPolicyRequest;
import com.bablsoft.accessflow.security.internal.web.model.RowLimitPolicyListResponse;
import com.bablsoft.accessflow.security.internal.web.model.RowLimitPolicyResponse;
import com.bablsoft.accessflow.security.internal.web.model.UpdateRowLimitPolicyRequest;
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
@RequestMapping("/api/v1/datasources/{datasourceId}/row-limit-policies")
@Tag(name = "Row-limit policies",
        description = "Per-table SELECT row caps on a datasource (#934)")
@PreAuthorize("hasAuthority('PERM_ROW_LIMIT_POLICY_MANAGE')")
@RequiredArgsConstructor
@Slf4j
class RowLimitPolicyController {

    private final RowLimitPolicyAdminService rowLimitPolicyAdminService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List row-limit policies configured on a datasource")
    @ApiResponse(responseCode = "200", description = "List of row-limit policies")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    RowLimitPolicyListResponse list(@PathVariable UUID datasourceId,
                                  Authentication authentication) {
        var caller = currentClaims(authentication);
        var policies = rowLimitPolicyAdminService
                .listForDatasource(datasourceId, caller.organizationId()).stream()
                .map(RowLimitPolicyResponse::from)
                .toList();
        return new RowLimitPolicyListResponse(policies);
    }

    @PostMapping
    @Operation(summary = "Create a row-limit policy on a datasource")
    @ApiResponse(responseCode = "201", description = "Row-limit policy created")
    @ApiResponse(responseCode = "400", description = "Bean Validation failure (blank table, max rows missing or out of range)")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    @ApiResponse(responseCode = "422", description = "Invalid table, max rows, or applies-to target")
    ResponseEntity<RowLimitPolicyResponse> create(
            @PathVariable UUID datasourceId,
            @Valid @RequestBody CreateRowLimitPolicyRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new CreateRowLimitPolicyCommand(
                request.schemaName(),
                request.tableName(),
                request.maxRows(),
                request.appliesToRoles(),
                request.appliesToGroupIds(),
                request.appliesToUserIds(),
                request.enabled());
        var view = rowLimitPolicyAdminService.create(datasourceId, caller.organizationId(), command);
        recordAudit(AuditAction.ROW_LIMIT_POLICY_CREATED, view, caller, auditContext);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{policyId}")
                .buildAndExpand(view.id())
                .toUri();
        return ResponseEntity.created(location).body(RowLimitPolicyResponse.from(view));
    }

    @PutMapping("/{policyId}")
    @Operation(summary = "Update a row-limit policy")
    @ApiResponse(responseCode = "200", description = "Row-limit policy updated")
    @ApiResponse(responseCode = "400", description = "Bean Validation failure (blank table, max rows missing or out of range)")
    @ApiResponse(responseCode = "404", description = "Datasource or policy not found")
    @ApiResponse(responseCode = "422", description = "Invalid table, max rows, or applies-to target")
    RowLimitPolicyResponse update(
            @PathVariable UUID datasourceId,
            @PathVariable UUID policyId,
            @Valid @RequestBody UpdateRowLimitPolicyRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new UpdateRowLimitPolicyCommand(
                request.schemaName(),
                request.tableName(),
                request.maxRows(),
                request.appliesToRoles(),
                request.appliesToGroupIds(),
                request.appliesToUserIds(),
                request.enabled());
        var view = rowLimitPolicyAdminService.update(policyId, datasourceId,
                caller.organizationId(), command);
        recordAudit(AuditAction.ROW_LIMIT_POLICY_UPDATED, view, caller, auditContext);
        return RowLimitPolicyResponse.from(view);
    }

    @DeleteMapping("/{policyId}")
    @Operation(summary = "Delete a row-limit policy")
    @ApiResponse(responseCode = "204", description = "Row-limit policy deleted")
    @ApiResponse(responseCode = "404", description = "Datasource or policy not found")
    ResponseEntity<Void> delete(@PathVariable UUID datasourceId,
                                @PathVariable UUID policyId,
                                Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        rowLimitPolicyAdminService.delete(policyId, datasourceId, caller.organizationId());
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", datasourceId.toString());
        recordAudit(AuditAction.ROW_LIMIT_POLICY_DELETED, AuditResourceType.ROW_LIMIT_POLICY,
                policyId, caller, auditContext, metadata);
        return ResponseEntity.noContent().build();
    }

    private void recordAudit(AuditAction action, RowLimitPolicyView view, JwtClaims caller,
                             RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", view.datasourceId().toString());
        if (view.schemaName() != null) {
            metadata.put("schema_name", view.schemaName());
        }
        metadata.put("table_name", view.tableName());
        metadata.put("max_rows", view.maxRows());
        metadata.put("enabled", view.enabled());
        recordAudit(action, AuditResourceType.ROW_LIMIT_POLICY, view.id(), caller, auditContext,
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
