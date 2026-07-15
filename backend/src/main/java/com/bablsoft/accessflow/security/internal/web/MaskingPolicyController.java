package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.CreateMaskingPolicyCommand;
import com.bablsoft.accessflow.core.api.MaskingPolicyAdminService;
import com.bablsoft.accessflow.core.api.MaskingPolicyView;
import com.bablsoft.accessflow.core.api.UpdateMaskingPolicyCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.CreateMaskingPolicyRequest;
import com.bablsoft.accessflow.security.internal.web.model.MaskingPolicyListResponse;
import com.bablsoft.accessflow.security.internal.web.model.MaskingPolicyResponse;
import com.bablsoft.accessflow.security.internal.web.model.UpdateMaskingPolicyRequest;
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
@RequestMapping("/api/v1/datasources/{datasourceId}/masking-policies")
@Tag(name = "Masking policies",
        description = "Per-column dynamic data masking policies with conditional reveal")
@PreAuthorize("hasAuthority('PERM_MASKING_POLICY_MANAGE')")
@RequiredArgsConstructor
@Slf4j
class MaskingPolicyController {

    private final MaskingPolicyAdminService maskingPolicyAdminService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List masking policies configured on a datasource")
    @ApiResponse(responseCode = "200", description = "List of masking policies")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    MaskingPolicyListResponse list(@PathVariable UUID datasourceId,
                                   Authentication authentication) {
        var caller = currentClaims(authentication);
        var policies = maskingPolicyAdminService
                .listForDatasource(datasourceId, caller.organizationId()).stream()
                .map(MaskingPolicyResponse::from)
                .toList();
        return new MaskingPolicyListResponse(policies);
    }

    @PostMapping
    @Operation(summary = "Create a masking policy on a datasource column")
    @ApiResponse(responseCode = "201", description = "Masking policy created")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    @ApiResponse(responseCode = "422", description = "Invalid strategy params or reveal target")
    ResponseEntity<MaskingPolicyResponse> create(
            @PathVariable UUID datasourceId,
            @Valid @RequestBody CreateMaskingPolicyRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new CreateMaskingPolicyCommand(
                request.columnRef(),
                request.strategy(),
                request.strategyParams(),
                request.revealToRoles(),
                request.revealToGroupIds(),
                request.revealToUserIds(),
                request.enabled());
        var view = maskingPolicyAdminService.create(datasourceId, caller.organizationId(), command);
        recordAudit(AuditAction.MASKING_POLICY_CREATED, view, caller, auditContext);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{policyId}")
                .buildAndExpand(view.id())
                .toUri();
        return ResponseEntity.created(location).body(MaskingPolicyResponse.from(view));
    }

    @PutMapping("/{policyId}")
    @Operation(summary = "Update a masking policy")
    @ApiResponse(responseCode = "200", description = "Masking policy updated")
    @ApiResponse(responseCode = "404", description = "Datasource or policy not found")
    @ApiResponse(responseCode = "422", description = "Invalid strategy params or reveal target")
    MaskingPolicyResponse update(
            @PathVariable UUID datasourceId,
            @PathVariable UUID policyId,
            @Valid @RequestBody UpdateMaskingPolicyRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new UpdateMaskingPolicyCommand(
                request.columnRef(),
                request.strategy(),
                request.strategyParams(),
                request.revealToRoles(),
                request.revealToGroupIds(),
                request.revealToUserIds(),
                request.enabled());
        var view = maskingPolicyAdminService.update(policyId, datasourceId,
                caller.organizationId(), command);
        recordAudit(AuditAction.MASKING_POLICY_UPDATED, view, caller, auditContext);
        return MaskingPolicyResponse.from(view);
    }

    @DeleteMapping("/{policyId}")
    @Operation(summary = "Delete a masking policy")
    @ApiResponse(responseCode = "204", description = "Masking policy deleted")
    @ApiResponse(responseCode = "404", description = "Datasource or policy not found")
    ResponseEntity<Void> delete(@PathVariable UUID datasourceId,
                                @PathVariable UUID policyId,
                                Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        maskingPolicyAdminService.delete(policyId, datasourceId, caller.organizationId());
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", datasourceId.toString());
        recordAudit(AuditAction.MASKING_POLICY_DELETED, AuditResourceType.MASKING_POLICY, policyId,
                caller, auditContext, metadata);
        return ResponseEntity.noContent().build();
    }

    private void recordAudit(AuditAction action, MaskingPolicyView view, JwtClaims caller,
                             RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", view.datasourceId().toString());
        metadata.put("column_ref", view.columnRef());
        metadata.put("strategy", view.strategy().name());
        metadata.put("enabled", view.enabled());
        recordAudit(action, AuditResourceType.MASKING_POLICY, view.id(), caller, auditContext,
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
