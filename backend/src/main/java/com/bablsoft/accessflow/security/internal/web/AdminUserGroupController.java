package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.CreateUserGroupCommand;
import com.bablsoft.accessflow.core.api.UpdateUserGroupCommand;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.AddUserGroupMemberRequest;
import com.bablsoft.accessflow.security.internal.web.model.CreateUserGroupRequest;
import com.bablsoft.accessflow.security.internal.web.model.UpdateUserGroupRequest;
import com.bablsoft.accessflow.security.internal.web.model.UserGroupMemberListResponse;
import com.bablsoft.accessflow.security.internal.web.model.UserGroupMemberResponse;
import com.bablsoft.accessflow.security.internal.web.model.UserGroupPageResponse;
import com.bablsoft.accessflow.security.internal.web.model.UserGroupResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
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
@RequestMapping("/api/v1/admin/groups")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin User Groups", description = "User group management endpoints (ADMIN only)")
@RequiredArgsConstructor
@Slf4j
class AdminUserGroupController {

    private final UserGroupService userGroupService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List user groups in the caller's organization (paginated)")
    @ApiResponse(responseCode = "200", description = "Page of groups")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    UserGroupPageResponse listGroups(Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = userGroupService.listGroups(caller.organizationId(),
                        SpringPageableAdapter.toPageRequest(pageable))
                .map(UserGroupResponse::from);
        return UserGroupPageResponse.from(page);
    }

    @PostMapping
    @Operation(summary = "Create a new user group in the caller's organization")
    @ApiResponse(responseCode = "201", description = "Group created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "Group with this name already exists")
    ResponseEntity<UserGroupResponse> createGroup(@Valid @RequestBody CreateUserGroupRequest request,
                                                  Authentication authentication,
                                                  RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new CreateUserGroupCommand(caller.organizationId(), request.name(),
                request.description());
        var created = userGroupService.createGroup(command);
        recordAudit(AuditAction.USER_GROUP_CREATED, created.id(), caller, auditContext,
                Map.of("name", created.name()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(UserGroupResponse.from(created));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a user group by id")
    @ApiResponse(responseCode = "200", description = "Group details")
    @ApiResponse(responseCode = "404", description = "Group not found in caller's organization")
    UserGroupResponse getGroup(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return UserGroupResponse.from(userGroupService.getGroup(id, caller.organizationId()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a user group's name or description")
    @ApiResponse(responseCode = "200", description = "Group updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Group not found")
    @ApiResponse(responseCode = "409", description = "Name conflict with another group")
    UserGroupResponse updateGroup(@PathVariable UUID id,
                                  @Valid @RequestBody UpdateUserGroupRequest request,
                                  Authentication authentication,
                                  RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new UpdateUserGroupCommand(request.name(), request.description());
        var updated = userGroupService.updateGroup(id, caller.organizationId(), command);
        recordAudit(AuditAction.USER_GROUP_UPDATED, id, caller, auditContext,
                Map.of("name", updated.name()));
        return UserGroupResponse.from(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a user group (cascades to memberships and reviewer assignments)")
    @ApiResponse(responseCode = "204", description = "Group deleted")
    @ApiResponse(responseCode = "404", description = "Group not found")
    ResponseEntity<Void> deleteGroup(@PathVariable UUID id, Authentication authentication,
                                     RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        userGroupService.deleteGroup(id, caller.organizationId());
        recordAudit(AuditAction.USER_GROUP_DELETED, id, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "List members of a user group")
    @ApiResponse(responseCode = "200", description = "List of members")
    @ApiResponse(responseCode = "404", description = "Group not found")
    UserGroupMemberListResponse listMembers(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        var members = userGroupService.listMembers(id, caller.organizationId()).stream()
                .map(UserGroupMemberResponse::from)
                .toList();
        return new UserGroupMemberListResponse(members);
    }

    @PostMapping("/{id}/members")
    @Operation(summary = "Add a user as a member of the group")
    @ApiResponse(responseCode = "201", description = "Member added")
    @ApiResponse(responseCode = "404", description = "Group or user not found")
    ResponseEntity<UserGroupMemberResponse> addMember(@PathVariable UUID id,
                                                     @Valid @RequestBody AddUserGroupMemberRequest request,
                                                     Authentication authentication,
                                                     RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var member = userGroupService.addMember(id, request.userId(), caller.organizationId());
        recordAudit(AuditAction.USER_GROUP_MEMBER_ADDED, id, caller, auditContext,
                Map.of("user_id", request.userId().toString()));
        return ResponseEntity.status(201).body(UserGroupMemberResponse.from(member));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @Operation(summary = "Remove a user from the group")
    @ApiResponse(responseCode = "204", description = "Member removed")
    @ApiResponse(responseCode = "404", description = "Group or membership not found")
    ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID userId,
                                      Authentication authentication,
                                      RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        userGroupService.removeMember(id, userId, caller.organizationId());
        recordAudit(AuditAction.USER_GROUP_MEMBER_REMOVED, id, caller, auditContext,
                Map.of("user_id", userId.toString()));
        return ResponseEntity.noContent().build();
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.USER_GROUP,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on user_group {}", action, resourceId, ex);
        }
    }
}
