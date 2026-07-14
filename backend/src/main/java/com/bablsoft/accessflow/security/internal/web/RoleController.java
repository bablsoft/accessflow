package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.CreateRoleCommand;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.RoleAdminService;
import com.bablsoft.accessflow.core.api.UpdateRoleCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.CreateRoleRequest;
import com.bablsoft.accessflow.security.internal.web.model.RoleListResponse;
import com.bablsoft.accessflow.security.internal.web.model.RoleResponse;
import com.bablsoft.accessflow.security.internal.web.model.UpdateRoleRequest;
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

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin CRUD over roles (AF-522): the 5 immutable global system roles plus the organization's
 * custom roles composed from the fixed permission catalog.
 */
@RestController
@RequestMapping("/api/v1/admin/roles")
@Tag(name = "Roles", description = "Custom role management endpoints")
@PreAuthorize("hasAuthority('PERM_ROLE_MANAGE')")
@RequiredArgsConstructor
@Slf4j
class RoleController {

    private final RoleAdminService roleAdminService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List the system and custom roles visible to the caller's organization")
    @ApiResponse(responseCode = "200", description = "Roles for the org")
    RoleListResponse list(Authentication authentication) {
        var caller = currentClaims(authentication);
        var roles = roleAdminService.list(caller.organizationId()).stream()
                .map(RoleResponse::from)
                .toList();
        return new RoleListResponse(roles);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single role by id")
    @ApiResponse(responseCode = "200", description = "Role details")
    @ApiResponse(responseCode = "404", description = "Role not found in caller's scope")
    RoleResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return RoleResponse.from(roleAdminService.get(id, caller.organizationId()));
    }

    @PostMapping
    @Operation(summary = "Create a custom role from a subset of the permission catalog")
    @ApiResponse(responseCode = "201", description = "Role created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "A role with this name already exists")
    ResponseEntity<RoleResponse> create(@Valid @RequestBody CreateRoleRequest request,
                                        Authentication authentication,
                                        RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var created = roleAdminService.create(new CreateRoleCommand(
                caller.organizationId(), request.name(), request.description(),
                request.permissions()));
        recordAudit(AuditAction.ROLE_CREATED, created.id(), caller, auditContext,
                Map.of("name", created.name(), "permissions", permissionNames(created.permissions())));
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(RoleResponse.from(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a custom role's name, description, or permission set")
    @ApiResponse(responseCode = "200", description = "Role updated")
    @ApiResponse(responseCode = "404", description = "Role not found in caller's scope")
    @ApiResponse(responseCode = "409", description = "System role (immutable) or name conflict")
    RoleResponse update(@PathVariable UUID id,
                        @Valid @RequestBody UpdateRoleRequest request,
                        Authentication authentication,
                        RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var updated = roleAdminService.update(id, caller.organizationId(), new UpdateRoleCommand(
                request.name(), request.description(), request.permissions()));
        recordAudit(AuditAction.ROLE_UPDATED, updated.id(), caller, auditContext,
                Map.of("name", updated.name(), "permissions", permissionNames(updated.permissions())));
        return RoleResponse.from(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a custom role that is not assigned to any user")
    @ApiResponse(responseCode = "204", description = "Role deleted")
    @ApiResponse(responseCode = "404", description = "Role not found in caller's scope")
    @ApiResponse(responseCode = "409", description = "System role (immutable) or role still assigned to users")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        roleAdminService.delete(id, caller.organizationId());
        recordAudit(AuditAction.ROLE_DELETED, id, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private static String permissionNames(Set<Permission> permissions) {
        return permissions.stream().map(Permission::name).sorted()
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    private void recordAudit(AuditAction action, UUID roleId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.ROLE,
                    roleId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on role {}", action, roleId, ex);
        }
    }
}
