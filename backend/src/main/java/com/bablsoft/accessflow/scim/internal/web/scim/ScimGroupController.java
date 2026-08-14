package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.scim.api.ScimPrincipal;
import com.bablsoft.accessflow.scim.internal.ScimGroupOrchestrator;
import com.bablsoft.accessflow.scim.internal.protocol.ScimGroupResource;
import com.bablsoft.accessflow.scim.internal.protocol.ScimListResponse;
import com.bablsoft.accessflow.scim.internal.protocol.ScimPatchRequest;
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
 * SCIM 2.0 Groups endpoint (#621). Member writes are tracked with {@code source=SCIM} and never
 * touch MANUAL or SSO-IDP memberships. Deleting a group cascades its memberships and group-based
 * grants — the correct semantics for "group removed at the IdP".
 */
@RestController
@RequestMapping(path = "/scim/v2/Groups",
        produces = {ScimMediaTypes.SCIM_JSON_VALUE, "application/json"})
@Tag(name = "SCIM Groups", description = "SCIM 2.0 group provisioning for identity providers")
@RequiredArgsConstructor
class ScimGroupController {

    private final ScimGroupOrchestrator orchestrator;
    private final ScimAuditWriter auditWriter;

    @GetMapping
    @Operation(summary = "List or filter groups (displayName/externalId eq)")
    @ApiResponse(responseCode = "200", description = "SCIM ListResponse")
    @ApiResponse(responseCode = "400", description = "Unsupported filter (invalidFilter)")
    @ApiResponse(responseCode = "401", description = "Invalid or missing bearer token")
    ScimListResponse<ScimGroupResource> list(
            @RequestParam(required = false) String filter,
            @RequestParam(defaultValue = "1") int startIndex,
            @RequestParam(defaultValue = "100") int count,
            Authentication authentication) {
        return orchestrator.list(principal(authentication), filter, startIndex, count, baseUrl());
    }

    @PostMapping
    @Operation(summary = "Create a group (optionally with members)")
    @ApiResponse(responseCode = "201", description = "Group created")
    @ApiResponse(responseCode = "409", description = "displayName or externalId already exists (uniqueness)")
    ResponseEntity<ScimGroupResource> create(@RequestBody ScimGroupResource resource,
                                             Authentication authentication,
                                             RequestAuditContext auditContext) {
        var principal = principal(authentication);
        var created = orchestrator.create(principal, resource, baseUrl());
        auditGroupSynced(created, principal, auditContext);
        return ResponseEntity.created(URI.create(created.meta().location())).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a group with members")
    @ApiResponse(responseCode = "200", description = "The group")
    @ApiResponse(responseCode = "404", description = "Unknown group in this organization")
    ScimGroupResource get(@PathVariable UUID id, Authentication authentication) {
        return orchestrator.get(principal(authentication), id, baseUrl());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace displayName, externalId, and the SCIM-sourced member set")
    @ApiResponse(responseCode = "200", description = "Updated group")
    @ApiResponse(responseCode = "404", description = "Unknown group in this organization")
    @ApiResponse(responseCode = "409", description = "displayName or externalId already exists (uniqueness)")
    ScimGroupResource replace(@PathVariable UUID id,
                              @RequestBody ScimGroupResource resource,
                              Authentication authentication,
                              RequestAuditContext auditContext) {
        var principal = principal(authentication);
        var updated = orchestrator.replace(principal, id, resource, baseUrl());
        auditGroupSynced(updated, principal, auditContext);
        return updated;
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Apply a SCIM PatchOp (members add/remove/replace, displayName, externalId)")
    @ApiResponse(responseCode = "200", description = "Updated group")
    @ApiResponse(responseCode = "400", description = "Unsupported op/path (invalidPath, invalidValue)")
    @ApiResponse(responseCode = "404", description = "Unknown group in this organization")
    ScimGroupResource patch(@PathVariable UUID id,
                            @RequestBody ScimPatchRequest patch,
                            Authentication authentication,
                            RequestAuditContext auditContext) {
        var principal = principal(authentication);
        var updated = orchestrator.patch(principal, id, patch, baseUrl());
        auditGroupSynced(updated, principal, auditContext);
        return updated;
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a group (cascades memberships and group-based grants)")
    @ApiResponse(responseCode = "204", description = "Group deleted")
    @ApiResponse(responseCode = "404", description = "Unknown group in this organization")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var principal = principal(authentication);
        var deleted = orchestrator.delete(principal, id);
        auditWriter.record(AuditAction.SCIM_GROUP_DELETED, AuditResourceType.USER_GROUP, id,
                principal,
                Map.of("name", String.valueOf(deleted.name()),
                        "member_count", deleted.memberCount()),
                auditContext);
        return ResponseEntity.noContent().build();
    }

    private void auditGroupSynced(ScimGroupResource group, ScimPrincipal principal,
                                  RequestAuditContext auditContext) {
        auditWriter.record(AuditAction.SCIM_GROUP_SYNCED, AuditResourceType.USER_GROUP,
                UUID.fromString(group.id()), principal,
                Map.of("name", String.valueOf(group.displayName()),
                        "member_count", group.members() == null ? 0 : group.members().size()),
                auditContext);
    }

    private static ScimPrincipal principal(Authentication authentication) {
        return (ScimPrincipal) authentication.getPrincipal();
    }

    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path("/scim/v2").toUriString();
    }
}
