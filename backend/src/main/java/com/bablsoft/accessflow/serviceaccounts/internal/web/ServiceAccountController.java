package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountAdminService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountDelegationView;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin lifecycle of service accounts and their API keys (#871). The service owns every decision;
 * the controller binds, delegates, maps and writes the audit row synchronously so
 * {@code ip_address} / {@code user_agent} come from the live request. A raw key never enters
 * audit metadata.
 */
@RestController
@RequestMapping("/api/v1/admin/service-accounts")
@PreAuthorize("hasAuthority('PERM_SERVICE_ACCOUNT_MANAGE')")
@Tag(name = "Service Accounts",
        description = "Admin management of non-human identities and the API keys issued on their "
                + "behalf (epic #867)")
@RequiredArgsConstructor
@Slf4j
class ServiceAccountController {

    private final ServiceAccountAdminService adminService;
    private final ServiceAccountDelegationService delegationService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;

    @GetMapping
    @Operation(summary = "List the organization's service accounts, newest first")
    @ApiResponse(responseCode = "200", description = "Page of service accounts (without key lists)")
    @ApiResponse(responseCode = "403", description = "Caller lacks SERVICE_ACCOUNT_MANAGE")
    ServiceAccountPageResponse list(@RequestParam(name = "managed_by", required = false) ServiceAccountSource managedBy,
                                    Pageable pageable, Authentication authentication) {
        var caller = currentClaims(authentication);
        return ServiceAccountPageResponse.from(adminService.list(caller.organizationId(), managedBy,
                SpringPageableAdapter.toPageRequest(pageable)));
    }

    @GetMapping("/mcp-tools")
    @Operation(summary = "The MCP tool names a service account's allow-list may reference")
    @ApiResponse(responseCode = "200", description = "Tool catalog, in the order the MCP server advertises it")
    @ApiResponse(responseCode = "403", description = "Caller lacks SERVICE_ACCOUNT_MANAGE")
    McpToolCatalogResponse mcpTools() {
        return McpToolCatalogResponse.current();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a service account with its API keys")
    @ApiResponse(responseCode = "200", description = "Service account")
    @ApiResponse(responseCode = "403", description = "Caller lacks SERVICE_ACCOUNT_MANAGE")
    @ApiResponse(responseCode = "404", description = "Service account not found")
    ServiceAccountResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return ServiceAccountResponse.from(adminService.get(caller.organizationId(), id));
    }

    @PostMapping
    @Operation(summary = "Create a service account (API-key-only identity, default role READONLY)")
    @ApiResponse(responseCode = "201", description = "Service account created; no key issued yet")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller lacks SERVICE_ACCOUNT_MANAGE")
    @ApiResponse(responseCode = "404", description = "Role not found")
    @ApiResponse(responseCode = "409", description = "Email already exists, or the user quota is exhausted")
    @ApiResponse(responseCode = "422", description = "Invalid owner, or an unknown MCP tool in the allow-list")
    ResponseEntity<ServiceAccountResponse> create(@Valid @RequestBody CreateServiceAccountRequest body,
                                                  Authentication authentication,
                                                  RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var created = adminService.create(caller.organizationId(), body.toCommand());
        recordAudit(AuditAction.SERVICE_ACCOUNT_CREATED, created.id(), caller, auditContext,
                Map.of("email", created.email(), "role", String.valueOf(created.roleName())));
        return ResponseEntity.created(accountLocation(created.id())).body(ServiceAccountResponse.from(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a service account — every field null-means-unchanged; UI-owned fields "
            + "are reset by naming them in 'clear'")
    @ApiResponse(responseCode = "200", description = "Service account updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller lacks SERVICE_ACCOUNT_MANAGE")
    @ApiResponse(responseCode = "404", description = "Service account or role not found")
    @ApiResponse(responseCode = "409", description = "A bootstrap-declared field would change on a "
            + "BOOTSTRAP-managed account")
    @ApiResponse(responseCode = "422", description = "Invalid owner, or an unknown MCP tool in the allow-list")
    ServiceAccountResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody UpdateServiceAccountRequest body,
                                  Authentication authentication,
                                  RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var updated = adminService.update(caller.organizationId(), id, caller.userId(), body.toCommand());
        recordAudit(AuditAction.SERVICE_ACCOUNT_UPDATED, id, caller, auditContext,
                Map.of("fields", body.presentFields(), "cleared", body.clearedFields()));
        return ServiceAccountResponse.from(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate a service account (its keys stop authenticating; nothing is revoked)")
    @ApiResponse(responseCode = "204", description = "Service account deactivated")
    @ApiResponse(responseCode = "403", description = "Caller lacks SERVICE_ACCOUNT_MANAGE")
    @ApiResponse(responseCode = "404", description = "Service account not found")
    ResponseEntity<Void> deactivate(@PathVariable UUID id, Authentication authentication,
                                    RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        adminService.deactivate(caller.organizationId(), id, caller.userId());
        recordAudit(AuditAction.SERVICE_ACCOUNT_DEACTIVATED, id, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/api-keys")
    @Operation(summary = "Issue an API key on behalf of the service account — plaintext shown once")
    @ApiResponse(responseCode = "201", description = "Key issued; raw_key is the only copy of the secret")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller lacks SERVICE_ACCOUNT_MANAGE")
    @ApiResponse(responseCode = "404", description = "Service account not found")
    @ApiResponse(responseCode = "409", description = "The account already has a key with that name")
    ResponseEntity<ServiceAccountIssuedKeyResponse> issueKey(@PathVariable UUID id,
                                                             @Valid @RequestBody IssueServiceAccountKeyRequest body,
                                                             Authentication authentication,
                                                             RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var issued = adminService.issueKey(caller.organizationId(), id, body.toCommand());
        recordAudit(AuditAction.SERVICE_ACCOUNT_KEY_ISSUED, id, caller, auditContext,
                Map.of("api_key_id", issued.apiKey().id().toString(), "name", issued.apiKey().name()));
        return ResponseEntity.created(accountLocation(id)).body(ServiceAccountIssuedKeyResponse.from(issued));
    }

    @PostMapping("/{id}/api-keys/{keyId}/rotate")
    @Operation(summary = "Rotate an API key — issue the replacement and expire the old one after a grace window")
    @ApiResponse(responseCode = "201", description = "Replacement issued; the superseded key expires at the "
            + "end of the grace window")
    @ApiResponse(responseCode = "400", description = "Validation error (blank name, non-positive grace)")
    @ApiResponse(responseCode = "403", description = "Caller lacks SERVICE_ACCOUNT_MANAGE")
    @ApiResponse(responseCode = "404", description = "Service account or key not found")
    @ApiResponse(responseCode = "409", description = "Bootstrap-declared key, already-revoked key, or the "
            + "replacement name is taken")
    ResponseEntity<ServiceAccountRotatedKeyResponse> rotateKey(@PathVariable UUID id,
                                                               @PathVariable UUID keyId,
                                                               @Valid @RequestBody RotateServiceAccountKeyRequest body,
                                                               Authentication authentication,
                                                               RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var rotated = adminService.rotateKey(caller.organizationId(), id, keyId, body.toCommand());
        recordAudit(AuditAction.SERVICE_ACCOUNT_KEY_ROTATED, id, caller, auditContext, Map.of(
                "api_key_id", rotated.apiKey().id().toString(),
                "superseded_key_id", rotated.supersededKey().id().toString(),
                "name", rotated.apiKey().name(),
                "superseded_expires_at", String.valueOf(rotated.supersededKey().expiresAt())));
        return ResponseEntity.created(accountLocation(id)).body(ServiceAccountRotatedKeyResponse.from(rotated));
    }

    @DeleteMapping("/{id}/api-keys/{keyId}")
    @Operation(summary = "Revoke an API key immediately (a bootstrap-declared key is refused)")
    @ApiResponse(responseCode = "204", description = "Key revoked (idempotent)")
    @ApiResponse(responseCode = "403", description = "Caller lacks SERVICE_ACCOUNT_MANAGE")
    @ApiResponse(responseCode = "404", description = "Service account or key not found")
    @ApiResponse(responseCode = "409", description = "The key is bootstrap-declared — rotate the secret at the "
            + "bootstrap source and restart")
    ResponseEntity<Void> revokeKey(@PathVariable UUID id, @PathVariable UUID keyId,
                                   Authentication authentication, RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        adminService.revokeKey(caller.organizationId(), id, keyId);
        recordAudit(AuditAction.SERVICE_ACCOUNT_KEY_REVOKED, id, caller, auditContext,
                Map.of("api_key_id", keyId.toString()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/delegated-principals")
    @Operation(summary = "List the humans this service account may act on behalf of (#874)")
    @ApiResponse(responseCode = "200", description = "Grants, newest first, revoked and expired included")
    @ApiResponse(responseCode = "403", description = "Caller lacks SERVICE_ACCOUNT_MANAGE or USER_MANAGE")
    @ApiResponse(responseCode = "404", description = "Service account not found")
    @PreAuthorize("hasAnyAuthority('PERM_SERVICE_ACCOUNT_MANAGE','PERM_USER_MANAGE')")
    List<ServiceAccountDelegationResponse> listDelegatedPrincipals(@PathVariable UUID id,
                                                                   Authentication authentication) {
        var caller = currentClaims(authentication);
        return delegationService.listForServiceAccount(caller.organizationId(), id).stream()
                .map(ServiceAccountDelegationResponse::from)
                .toList();
    }

    @PostMapping("/{id}/delegated-principals")
    @Operation(summary = "Let this service account act on behalf of a human (#874) — confers no permission")
    @ApiResponse(responseCode = "201", description = "Grant created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller lacks SERVICE_ACCOUNT_MANAGE or USER_MANAGE")
    @ApiResponse(responseCode = "404", description = "Service account not found")
    @ApiResponse(responseCode = "409", description = "A live grant for this human already exists")
    @ApiResponse(responseCode = "422", description = "Principal is not an active human of the organization, or expiry is past")
    @PreAuthorize("hasAnyAuthority('PERM_SERVICE_ACCOUNT_MANAGE','PERM_USER_MANAGE')")
    ResponseEntity<ServiceAccountDelegationResponse> grantDelegatedPrincipal(
            @PathVariable UUID id, @Valid @RequestBody GrantDelegatedPrincipalRequest body,
            Authentication authentication, RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var view = delegationService.grant(caller.organizationId(), caller.userId(), body.toCommand(id));
        recordAudit(AuditAction.SERVICE_ACCOUNT_DELEGATION_GRANTED, id, caller, auditContext,
                delegationMetadata(view));
        return ResponseEntity.status(HttpStatus.CREATED).body(ServiceAccountDelegationResponse.from(view));
    }

    @DeleteMapping("/{id}/delegated-principals/{delegationId}")
    @Operation(summary = "Revoke a delegated-principal grant (idempotent)")
    @ApiResponse(responseCode = "204", description = "Grant revoked")
    @ApiResponse(responseCode = "403", description = "Caller lacks SERVICE_ACCOUNT_MANAGE or USER_MANAGE")
    @ApiResponse(responseCode = "404", description = "Service account or grant not found")
    @PreAuthorize("hasAnyAuthority('PERM_SERVICE_ACCOUNT_MANAGE','PERM_USER_MANAGE')")
    ResponseEntity<Void> revokeDelegatedPrincipal(@PathVariable UUID id, @PathVariable UUID delegationId,
                                                  Authentication authentication,
                                                  RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        // Scoped to the account in the path: a grant of another account is a 404 here, so the
        // audit row can never name the wrong service account.
        var view = delegationService.revoke(caller.organizationId(), caller.userId(), delegationId, id, null);
        recordAudit(AuditAction.SERVICE_ACCOUNT_DELEGATION_REVOKED, id, caller, auditContext,
                delegationMetadata(view));
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> delegationMetadata(ServiceAccountDelegationView view) {
        var metadata = new HashMap<String, Object>();
        metadata.put("delegation_id", view.id().toString());
        metadata.put("principal_user_id", view.principalUserId().toString());
        metadata.put("channel", "admin");
        if (view.expiresAt() != null) {
            metadata.put("expires_at", view.expiresAt().toString());
        }
        return metadata;
    }

    /**
     * A body that will not deserialize (an unknown {@code role} literal, a malformed
     * {@code grace_period}) or an unknown {@code managed_by} query value is a client error. Nothing
     * maps the unreadable body globally, so without this the security module's {@code Exception}
     * catch-all turns it into a 500 — the sqlreview precedent; the type mismatch is also caught by
     * the global handler since #875, and stays here for the module-specific detail.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail handleUnreadableInput(Exception ex) {
        var detail = messageSource.getMessage("error.service_account_body_unreadable", null,
                LocaleContextHolder.getLocale());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("error", "VALIDATION_ERROR");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    private static URI accountLocation(UUID id) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/admin/service-accounts/{id}")
                .buildAndExpand(id)
                .toUri();
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.SERVICE_ACCOUNT,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on service_account {}", action, resourceId, ex);
        }
    }
}
