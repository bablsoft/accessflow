package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.lifecycle.api.CreateRetentionPolicyCommand;
import com.bablsoft.accessflow.lifecycle.api.RetentionPolicyService;
import com.bablsoft.accessflow.lifecycle.api.RetentionPolicyView;
import com.bablsoft.accessflow.lifecycle.api.UpdateRetentionPolicyCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/lifecycle/policies")
@Tag(name = "Lifecycle Policies", description = "Admin CRUD + dry-run for data retention policies")
@RequiredArgsConstructor
class RetentionPolicyController {

    private final RetentionPolicyService retentionPolicyService;
    private final LifecycleAuditWriter auditWriter;

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_RETENTION_POLICY_MANAGE')")
    @Operation(summary = "List retention policies in the organization")
    @ApiResponse(responseCode = "200", description = "Page of retention policies")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    RetentionPolicyPageResponse list(Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = retentionPolicyService.list(caller.organizationId(),
                SpringPageableAdapter.toPageRequest(pageable));
        return RetentionPolicyPageResponse.from(page);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_RETENTION_POLICY_MANAGE')")
    @Operation(summary = "Get a single retention policy")
    @ApiResponse(responseCode = "200", description = "The retention policy")
    @ApiResponse(responseCode = "404", description = "Retention policy not found")
    RetentionPolicyResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return RetentionPolicyResponse.from(retentionPolicyService.get(id, caller.organizationId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_RETENTION_POLICY_MANAGE')")
    @Operation(summary = "Create a retention policy")
    @ApiResponse(responseCode = "201", description = "Policy created")
    @ApiResponse(responseCode = "400", description = "Validation error or invalid policy")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    RetentionPolicyResponse create(@Valid @RequestBody CreateRetentionPolicyRequest body,
                                   Authentication authentication,
                                   RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var view = retentionPolicyService.create(new CreateRetentionPolicyCommand(
                caller.organizationId(), body.datasourceId(), body.name(), body.description(),
                body.targetTable(), body.targetColumns(), body.classificationTag(),
                body.timestampColumn(), body.retentionWindow(), body.action(), body.transformType(),
                body.softDeleteColumn(), body.conditions(), body.rawWhere(), body.cronSchedule(),
                body.enabled() == null || body.enabled(), caller.userId()));
        auditWriter.record(AuditAction.RETENTION_POLICY_CREATED, AuditResourceType.RETENTION_POLICY,
                view.id(), caller, metadata(view), auditContext);
        return RetentionPolicyResponse.from(view);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_RETENTION_POLICY_MANAGE')")
    @Operation(summary = "Update a retention policy")
    @ApiResponse(responseCode = "200", description = "Policy updated")
    @ApiResponse(responseCode = "400", description = "Validation error or invalid policy")
    @ApiResponse(responseCode = "404", description = "Retention policy not found")
    RetentionPolicyResponse update(@PathVariable UUID id,
                                   @Valid @RequestBody UpdateRetentionPolicyRequest body,
                                   Authentication authentication,
                                   RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var view = retentionPolicyService.update(new UpdateRetentionPolicyCommand(
                id, caller.organizationId(), body.name(), body.description(), body.targetTable(),
                body.targetColumns(), body.classificationTag(), body.timestampColumn(),
                body.retentionWindow(), body.action(), body.transformType(), body.softDeleteColumn(),
                body.conditions(), body.rawWhere(), body.cronSchedule(),
                body.enabled() == null || body.enabled()));
        auditWriter.record(AuditAction.RETENTION_POLICY_UPDATED, AuditResourceType.RETENTION_POLICY,
                view.id(), caller, metadata(view), auditContext);
        return RetentionPolicyResponse.from(view);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_RETENTION_POLICY_MANAGE')")
    @Operation(summary = "Delete a retention policy")
    @ApiResponse(responseCode = "204", description = "Policy deleted")
    @ApiResponse(responseCode = "404", description = "Retention policy not found")
    void delete(@PathVariable UUID id, Authentication authentication,
                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        retentionPolicyService.delete(id, caller.organizationId());
        auditWriter.record(AuditAction.RETENTION_POLICY_DELETED, AuditResourceType.RETENTION_POLICY,
                id, caller, Map.of(), auditContext);
    }

    @PostMapping("/{id}/preview")
    @PreAuthorize("hasAuthority('PERM_RETENTION_POLICY_MANAGE')")
    @Operation(summary = "Dry-run preview of a policy's impact, without executing")
    @ApiResponse(responseCode = "200", description = "Impact set (tables, estimated rows, method)")
    @ApiResponse(responseCode = "404", description = "Retention policy not found")
    LifecyclePreviewResponse preview(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return LifecyclePreviewResponse.from(
                retentionPolicyService.preview(id, caller.organizationId()));
    }

    private static Map<String, Object> metadata(RetentionPolicyView view) {
        return Map.of(
                "name", view.name(),
                "datasourceId", view.datasourceId().toString(),
                "action", view.action().name(),
                "enabled", view.enabled());
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
