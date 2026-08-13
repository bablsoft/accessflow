package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.scim.api.ScimPrincipal;
import com.bablsoft.accessflow.scim.internal.ScimUserOrchestrator;
import com.bablsoft.accessflow.scim.internal.ScimUserWriteResult;
import com.bablsoft.accessflow.scim.internal.protocol.ScimListResponse;
import com.bablsoft.accessflow.scim.internal.protocol.ScimPatchRequest;
import com.bablsoft.accessflow.scim.internal.protocol.ScimUserResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * SCIM 2.0 Users endpoint (#621). Paths are RFC 7644's PascalCase — a deliberate exception to the
 * kebab-case rule; authentication is the per-org bearer token chain, not JWT.
 */
@RestController
@RequestMapping(path = "/scim/v2/Users",
        produces = {ScimMediaTypes.SCIM_JSON_VALUE, "application/json"})
@Tag(name = "SCIM Users", description = "SCIM 2.0 user provisioning for identity providers")
@RequiredArgsConstructor
class ScimUserController {

    private final ScimUserOrchestrator orchestrator;
    private final ScimAuditWriter auditWriter;

    @GetMapping
    @Operation(summary = "List or filter users (userName/externalId/emails eq)")
    @ApiResponse(responseCode = "200", description = "SCIM ListResponse")
    @ApiResponse(responseCode = "400", description = "Unsupported filter (invalidFilter)")
    @ApiResponse(responseCode = "401", description = "Invalid or missing bearer token")
    ScimListResponse<ScimUserResource> list(
            @RequestParam(required = false) String filter,
            @RequestParam(defaultValue = "1") int startIndex,
            @RequestParam(defaultValue = "100") int count,
            Authentication authentication) {
        return orchestrator.list(principal(authentication), filter, startIndex, count, baseUrl());
    }

    @PostMapping
    @Operation(summary = "Provision a user")
    @ApiResponse(responseCode = "201", description = "User created")
    @ApiResponse(responseCode = "403", description = "Organization user quota exceeded")
    @ApiResponse(responseCode = "409", description = "Email or externalId already exists (uniqueness)")
    ResponseEntity<ScimUserResource> create(@RequestBody ScimUserResource resource,
                                            Authentication authentication,
                                            RequestAuditContext auditContext) {
        var principal = principal(authentication);
        var created = orchestrator.create(principal, resource, baseUrl());
        auditWriter.record(AuditAction.SCIM_USER_PROVISIONED, AuditResourceType.USER,
                UUID.fromString(created.id()), principal,
                Map.of("email", created.userName(),
                        "external_id", String.valueOf(created.externalId())),
                auditContext);
        return ResponseEntity.created(URI.create(created.meta().location())).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a user")
    @ApiResponse(responseCode = "200", description = "The user")
    @ApiResponse(responseCode = "404", description = "Unknown user in this organization")
    ScimUserResource get(@PathVariable UUID id, Authentication authentication) {
        return orchestrator.get(principal(authentication), id, baseUrl());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace the SCIM-owned attributes of a user")
    @ApiResponse(responseCode = "200", description = "Updated user")
    @ApiResponse(responseCode = "404", description = "Unknown user in this organization")
    @ApiResponse(responseCode = "409", description = "Email or externalId already exists (uniqueness)")
    ScimUserResource replace(@PathVariable UUID id,
                             @RequestBody ScimUserResource resource,
                             Authentication authentication,
                             RequestAuditContext auditContext) {
        var principal = principal(authentication);
        var result = orchestrator.replace(principal, id, resource, baseUrl());
        auditUserWrite(result, id, principal, auditContext);
        return result.resource();
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Apply a SCIM PatchOp (active, displayName, externalId, mapped email)")
    @ApiResponse(responseCode = "200", description = "Updated user")
    @ApiResponse(responseCode = "400", description = "Unsupported op/path (invalidPath, invalidValue)")
    @ApiResponse(responseCode = "404", description = "Unknown user in this organization")
    ScimUserResource patch(@PathVariable UUID id,
                           @RequestBody ScimPatchRequest patch,
                           Authentication authentication,
                           RequestAuditContext auditContext) {
        var principal = principal(authentication);
        var result = orchestrator.patch(principal, id, patch, baseUrl());
        auditUserWrite(result, id, principal, auditContext);
        return result.resource();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate a user (AccessFlow never hard-deletes)")
    @ApiResponse(responseCode = "204", description = "User deactivated (idempotent)")
    @ApiResponse(responseCode = "404", description = "Unknown user in this organization")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var principal = principal(authentication);
        if (orchestrator.delete(principal, id)) {
            auditWriter.record(AuditAction.SCIM_USER_DEACTIVATED, AuditResourceType.USER, id,
                    principal, Map.of("via", "DELETE"), auditContext);
        }
        return ResponseEntity.noContent().build();
    }

    private void auditUserWrite(ScimUserWriteResult result, UUID id, ScimPrincipal principal,
                                RequestAuditContext auditContext) {
        var action = result.deactivated()
                ? AuditAction.SCIM_USER_DEACTIVATED
                : AuditAction.SCIM_USER_UPDATED;
        auditWriter.record(action, AuditResourceType.USER, id, principal,
                Map.of("email", String.valueOf(result.resource().userName())), auditContext);
    }

    private static ScimPrincipal principal(Authentication authentication) {
        return (ScimPrincipal) authentication.getPrincipal();
    }

    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path("/scim/v2").toUriString();
    }
}
