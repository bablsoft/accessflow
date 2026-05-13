package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.InviteUserCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.api.UserInvitationService;
import com.bablsoft.accessflow.security.internal.web.model.InviteUserRequest;
import com.bablsoft.accessflow.security.internal.web.model.UserInvitationPageResponse;
import com.bablsoft.accessflow.security.internal.web.model.UserInvitationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users/invitations")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin User Invitations", description = "Invite users by email (ADMIN only)")
@RequiredArgsConstructor
@Slf4j
class AdminUserInvitationController {

    private final UserInvitationService invitationService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List user invitations for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Page of invitations")
    UserInvitationPageResponse list(Authentication authentication, Pageable pageable) {
        var caller = claims(authentication);
        var page = invitationService.list(caller.organizationId(),
                SpringPageableAdapter.toPageRequest(pageable));
        return UserInvitationPageResponse.from(page);
    }

    @PostMapping
    @Operation(summary = "Invite a user to join the caller's organization")
    @ApiResponse(responseCode = "201", description = "Invitation created and emailed")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "A pending invitation already exists for this email")
    @ApiResponse(responseCode = "422", description = "System SMTP is not configured")
    ResponseEntity<UserInvitationResponse> invite(@Valid @RequestBody InviteUserRequest request,
                                                  Authentication authentication,
                                                  RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var issued = invitationService.invite(
                new InviteUserCommand(request.email(), request.displayName(), request.role()),
                caller.organizationId(),
                caller.userId());
        recordAudit(AuditAction.USER_INVITED, issued.invitation().id(), caller, auditContext,
                Map.of("email", issued.invitation().email(), "role", issued.invitation().role().name()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UserInvitationResponse.from(issued.invitation()));
    }

    @PostMapping("/{id}/resend")
    @Operation(summary = "Reissue the token and resend the invitation email")
    @ApiResponse(responseCode = "200", description = "Invitation resent")
    @ApiResponse(responseCode = "404", description = "Invitation not found")
    @ApiResponse(responseCode = "422", description = "Invitation cannot be resent (accepted or revoked)")
    UserInvitationResponse resend(@PathVariable UUID id, Authentication authentication,
                                  RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var issued = invitationService.resend(id, caller.organizationId(), caller.userId());
        recordAudit(AuditAction.USER_INVITATION_RESENT, id, caller, auditContext, Map.of());
        return UserInvitationResponse.from(issued.invitation());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a pending invitation")
    @ApiResponse(responseCode = "204", description = "Invitation revoked")
    @ApiResponse(responseCode = "404", description = "Invitation not found")
    @ApiResponse(responseCode = "422", description = "Invitation already accepted")
    ResponseEntity<Void> revoke(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = claims(authentication);
        invitationService.revoke(id, caller.organizationId());
        recordAudit(AuditAction.USER_INVITATION_REVOKED, id, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    private JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.USER_INVITATION,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on invitation {}", action, resourceId, ex);
        }
    }
}
