package com.bablsoft.accessflow.scim.internal.web.admin;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.scim.api.ScimTokenService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/scim/tokens")
@PreAuthorize("hasAuthority('PERM_SSO_CONFIGURE')")
@Tag(name = "Admin SCIM Tokens", description = "SCIM bearer token management (#621)")
@RequiredArgsConstructor
@Slf4j
class ScimAdminTokenController {

    private final ScimTokenService tokenService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List the organization's SCIM bearer tokens (prefixes only)")
    @ApiResponse(responseCode = "200", description = "Tokens, newest first")
    List<ScimTokenResponse> list(Authentication authentication) {
        var caller = currentClaims(authentication);
        return tokenService.list(caller.organizationId()).stream()
                .map(ScimTokenResponse::from)
                .toList();
    }

    @PostMapping
    @Operation(summary = "Create a SCIM bearer token — the raw value is returned exactly once")
    @ApiResponse(responseCode = "201", description = "Token created; response carries the raw value")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "A token with this name already exists")
    ResponseEntity<CreatedScimTokenResponse> create(
            @Valid @RequestBody CreateScimTokenRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var issued = tokenService.create(caller.organizationId(), request.name(), caller.userId());
        recordAudit(AuditAction.SCIM_TOKEN_CREATED, issued.token().id(), caller, auditContext,
                Map.of("name", issued.token().name()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CreatedScimTokenResponse.from(issued));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a SCIM bearer token (idempotent)")
    @ApiResponse(responseCode = "204", description = "Token revoked")
    @ApiResponse(responseCode = "404", description = "Unknown token in this organization")
    ResponseEntity<Void> revoke(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        tokenService.revoke(caller.organizationId(), id);
        recordAudit(AuditAction.SCIM_TOKEN_REVOKED, id, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.SCIM_TOKEN,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {}", action, resourceId, ex);
        }
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
