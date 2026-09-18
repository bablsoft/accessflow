package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * A human's own consent surface (#874): "this agent may act for me". No permission beyond being
 * signed in — the grant confers nothing on the agent and only the caller can be the principal. A
 * service account reaching this with its key is rejected by the service (it cannot be a principal).
 */
@RestController
@RequestMapping("/api/v1/me/service-account-delegations")
@Tag(name = "Service Accounts")
@RequiredArgsConstructor
@Slf4j
class MeServiceAccountDelegationController {

    private final ServiceAccountDelegationService delegationService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List the service accounts allowed to act on my behalf (revoked and expired included)")
    @ApiResponse(responseCode = "200", description = "Grants, newest first")
    List<ServiceAccountDelegationResponse> list(Authentication authentication) {
        var caller = currentClaims(authentication);
        return delegationService.listForPrincipal(caller.organizationId(), caller.userId()).stream()
                .map(ServiceAccountDelegationResponse::from)
                .toList();
    }

    @PostMapping
    @Operation(summary = "Let a service account act on my behalf")
    @ApiResponse(responseCode = "201", description = "Grant created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Service account not found")
    @ApiResponse(responseCode = "409", description = "A live grant already exists")
    @ApiResponse(responseCode = "422", description = "Caller is not an active human, or expiry is in the past")
    ResponseEntity<ServiceAccountDelegationResponse> grant(
            @Valid @RequestBody GrantMyServiceAccountDelegationRequest body,
            Authentication authentication, RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var view = delegationService.grant(caller.organizationId(), caller.userId(), body.toCommand(caller.userId()));
        recordAudit(AuditAction.SERVICE_ACCOUNT_DELEGATION_GRANTED, view, caller, auditContext);
        return ResponseEntity.status(HttpStatus.CREATED).body(ServiceAccountDelegationResponse.from(view));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a grant I gave (idempotent)")
    @ApiResponse(responseCode = "204", description = "Grant revoked")
    @ApiResponse(responseCode = "404", description = "Grant not found or not mine")
    ResponseEntity<Void> revoke(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var view = delegationService.revoke(caller.organizationId(), caller.userId(), id, null, caller.userId());
        recordAudit(AuditAction.SERVICE_ACCOUNT_DELEGATION_REVOKED, view, caller, auditContext);
        return ResponseEntity.noContent().build();
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, ServiceAccountDelegationView view, JwtClaims caller,
                             RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("delegation_id", view.id().toString());
        metadata.put("principal_user_id", view.principalUserId().toString());
        metadata.put("channel", "self_service");
        if (view.expiresAt() != null) {
            metadata.put("expires_at", view.expiresAt().toString());
        }
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.SERVICE_ACCOUNT,
                    view.serviceAccountUserId(),
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on delegation {}", action, view.id(), ex);
        }
    }
}
