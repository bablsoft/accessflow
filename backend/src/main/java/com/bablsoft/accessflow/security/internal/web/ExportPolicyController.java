package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.CreateExportPolicyCommand;
import com.bablsoft.accessflow.core.api.ExportPolicyAdminService;
import com.bablsoft.accessflow.core.api.ExportPolicyView;
import com.bablsoft.accessflow.core.api.UpdateExportPolicyCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.CreateExportPolicyRequest;
import com.bablsoft.accessflow.security.internal.web.model.ExportPolicyListResponse;
import com.bablsoft.accessflow.security.internal.web.model.ExportPolicyResponse;
import com.bablsoft.accessflow.security.internal.web.model.UpdateExportPolicyRequest;
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
@RequestMapping("/api/v1/datasources/{datasourceId}/export-policies")
@Tag(name = "Result-export policies",
        description = "Per-datasource result-export governance (DLP) policies")
@PreAuthorize("hasAuthority('PERM_EXPORT_POLICY_MANAGE')")
@RequiredArgsConstructor
@Slf4j
class ExportPolicyController {

    private final ExportPolicyAdminService exportPolicyAdminService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List result-export policies configured on a datasource")
    @ApiResponse(responseCode = "200", description = "List of export policies")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    ExportPolicyListResponse list(@PathVariable UUID datasourceId,
                                  Authentication authentication) {
        var caller = currentClaims(authentication);
        var policies = exportPolicyAdminService
                .listForDatasource(datasourceId, caller.organizationId()).stream()
                .map(ExportPolicyResponse::from)
                .toList();
        return new ExportPolicyListResponse(policies);
    }

    @PostMapping
    @Operation(summary = "Create a result-export policy on a datasource")
    @ApiResponse(responseCode = "201", description = "Export policy created")
    @ApiResponse(responseCode = "400", description = "Bean Validation failure (missing mode, row cap out of range)")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    @ApiResponse(responseCode = "422", description = "Invalid mode, row cap, or applies-to target")
    ResponseEntity<ExportPolicyResponse> create(
            @PathVariable UUID datasourceId,
            @Valid @RequestBody CreateExportPolicyRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new CreateExportPolicyCommand(
                request.mode(),
                request.rowCap(),
                request.denyClassifications(),
                request.appliesToRoles(),
                request.appliesToGroupIds(),
                request.appliesToUserIds(),
                request.enabled());
        var view = exportPolicyAdminService.create(datasourceId, caller.organizationId(), command);
        recordAudit(AuditAction.EXPORT_POLICY_CREATED, view, caller, auditContext);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{policyId}")
                .buildAndExpand(view.id())
                .toUri();
        return ResponseEntity.created(location).body(ExportPolicyResponse.from(view));
    }

    @PutMapping("/{policyId}")
    @Operation(summary = "Update a result-export policy")
    @ApiResponse(responseCode = "200", description = "Export policy updated")
    @ApiResponse(responseCode = "400", description = "Bean Validation failure (missing mode, row cap out of range)")
    @ApiResponse(responseCode = "404", description = "Datasource or policy not found")
    @ApiResponse(responseCode = "422", description = "Invalid mode, row cap, or applies-to target")
    ExportPolicyResponse update(
            @PathVariable UUID datasourceId,
            @PathVariable UUID policyId,
            @Valid @RequestBody UpdateExportPolicyRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new UpdateExportPolicyCommand(
                request.mode(),
                request.rowCap(),
                request.denyClassifications(),
                request.appliesToRoles(),
                request.appliesToGroupIds(),
                request.appliesToUserIds(),
                request.enabled());
        var view = exportPolicyAdminService.update(policyId, datasourceId,
                caller.organizationId(), command);
        recordAudit(AuditAction.EXPORT_POLICY_UPDATED, view, caller, auditContext);
        return ExportPolicyResponse.from(view);
    }

    @DeleteMapping("/{policyId}")
    @Operation(summary = "Delete a result-export policy")
    @ApiResponse(responseCode = "204", description = "Export policy deleted")
    @ApiResponse(responseCode = "404", description = "Datasource or policy not found")
    ResponseEntity<Void> delete(@PathVariable UUID datasourceId,
                                @PathVariable UUID policyId,
                                Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        exportPolicyAdminService.delete(policyId, datasourceId, caller.organizationId());
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", datasourceId.toString());
        recordAudit(AuditAction.EXPORT_POLICY_DELETED, AuditResourceType.EXPORT_POLICY,
                policyId, caller, auditContext, metadata);
        return ResponseEntity.noContent().build();
    }

    private void recordAudit(AuditAction action, ExportPolicyView view, JwtClaims caller,
                             RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", view.datasourceId().toString());
        metadata.put("mode", view.mode().name());
        if (view.rowCap() != null) {
            metadata.put("row_cap", view.rowCap());
        }
        metadata.put("enabled", view.enabled());
        recordAudit(action, AuditResourceType.EXPORT_POLICY, view.id(), caller, auditContext,
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
