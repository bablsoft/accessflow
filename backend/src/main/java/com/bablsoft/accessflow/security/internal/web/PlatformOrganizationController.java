package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.CreateOrganizationCommand;
import com.bablsoft.accessflow.core.api.OrganizationAdminService;
import com.bablsoft.accessflow.core.api.UpdateOrganizationCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.CreateOrganizationRequest;
import com.bablsoft.accessflow.security.internal.web.model.OrganizationPageResponse;
import com.bablsoft.accessflow.security.internal.web.model.OrganizationResponse;
import com.bablsoft.accessflow.security.internal.web.model.OrganizationUsageResponse;
import com.bablsoft.accessflow.security.internal.web.model.UpdateOrganizationRequest;
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

/**
 * Cross-org organization management (AF-456). Unlike every other admin controller, these endpoints
 * are NOT scoped to the caller's organization — they operate on any organization by path id and are
 * reachable only by a platform admin ({@code PLATFORM_ADMIN} authority, granted orthogonally to the
 * home-org role). Audit rows are written against the TARGET organization, with the caller as actor.
 */
@RestController
@RequestMapping("/api/v1/platform/organizations")
@PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
@Tag(name = "Platform Organizations", description = "Cross-org management endpoints (platform admin only)")
@RequiredArgsConstructor
@Slf4j
class PlatformOrganizationController {

    private final OrganizationAdminService organizationAdminService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List all organizations in the cluster (paginated)")
    @ApiResponse(responseCode = "200", description = "Page of organizations")
    @ApiResponse(responseCode = "403", description = "Caller is not a platform admin")
    OrganizationPageResponse list(Pageable pageable) {
        var page = organizationAdminService.list(SpringPageableAdapter.toPageRequest(pageable))
                .map(OrganizationResponse::from);
        return OrganizationPageResponse.from(page);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single organization by id")
    @ApiResponse(responseCode = "200", description = "Organization")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    OrganizationResponse get(@PathVariable UUID id) {
        return OrganizationResponse.from(organizationAdminService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create a new organization with optional quotas")
    @ApiResponse(responseCode = "201", description = "Organization created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    ResponseEntity<OrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest request,
                                                Authentication authentication,
                                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var created = organizationAdminService.create(new CreateOrganizationCommand(
                request.name(),
                request.slug(),
                request.maxDatasources(),
                request.maxUsers(),
                request.maxQueriesPerDay()));
        recordAudit(AuditAction.ORGANIZATION_CREATED, created.id(), caller, auditContext,
                Map.of("name", created.name(), "slug", created.slug()));
        var response = OrganizationResponse.from(created);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an organization's name and quotas")
    @ApiResponse(responseCode = "200", description = "Organization updated")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    OrganizationResponse update(@PathVariable UUID id,
                                @Valid @RequestBody UpdateOrganizationRequest request,
                                Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var updated = organizationAdminService.update(id, new UpdateOrganizationCommand(
                request.name(),
                request.maxDatasources(),
                request.maxUsers(),
                request.maxQueriesPerDay()));
        recordAudit(AuditAction.ORGANIZATION_UPDATED, id, caller, auditContext, Map.of());
        return OrganizationResponse.from(updated);
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable an organization — blocks its users at login and at request time")
    @ApiResponse(responseCode = "204", description = "Organization disabled")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    ResponseEntity<Void> disable(@PathVariable UUID id, Authentication authentication,
                                 RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        organizationAdminService.setDisabled(id, true);
        recordAudit(AuditAction.ORGANIZATION_DISABLED, id, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Re-enable a disabled organization")
    @ApiResponse(responseCode = "204", description = "Organization enabled")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    ResponseEntity<Void> enable(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        organizationAdminService.setDisabled(id, false);
        recordAudit(AuditAction.ORGANIZATION_ENABLED, id, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/usage")
    @Operation(summary = "Current per-org resource usage against the configured quotas")
    @ApiResponse(responseCode = "200", description = "Usage snapshot")
    @ApiResponse(responseCode = "404", description = "Organization not found")
    OrganizationUsageResponse usage(@PathVariable UUID id) {
        return OrganizationUsageResponse.from(organizationAdminService.getUsage(id));
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, UUID targetOrganizationId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            // organizationId is the TARGET org (cross-org op); actor is the platform admin caller.
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.ORGANIZATION,
                    targetOrganizationId,
                    targetOrganizationId,
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on organization {}", action, targetOrganizationId, ex);
        }
    }
}
