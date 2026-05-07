package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.audit.api.AuditAction;
import com.partqam.accessflow.audit.api.AuditEntry;
import com.partqam.accessflow.audit.api.AuditLogService;
import com.partqam.accessflow.audit.api.AuditResourceType;
import com.partqam.accessflow.audit.api.RequestAuditContext;
import com.partqam.accessflow.core.api.CreateDatasourceCommand;
import com.partqam.accessflow.core.api.CreatePermissionCommand;
import com.partqam.accessflow.core.api.DatasourceAdminService;
import com.partqam.accessflow.core.api.DriverCatalogService;
import com.partqam.accessflow.core.api.UpdateDatasourceCommand;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.security.api.JwtClaims;
import com.partqam.accessflow.security.internal.web.model.ConnectionTestResponse;
import com.partqam.accessflow.security.internal.web.model.CreateDatasourceRequest;
import com.partqam.accessflow.security.internal.web.model.CreatePermissionRequest;
import com.partqam.accessflow.security.internal.web.model.DatabaseSchemaResponse;
import com.partqam.accessflow.security.internal.web.model.DatasourcePageResponse;
import com.partqam.accessflow.security.internal.web.model.DatasourceResponse;
import com.partqam.accessflow.security.internal.web.model.DatasourceTypesResponse;
import com.partqam.accessflow.security.internal.web.model.PermissionListResponse;
import com.partqam.accessflow.security.internal.web.model.PermissionResponse;
import com.partqam.accessflow.security.internal.web.model.UpdateDatasourceRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/v1/datasources")
@Tag(name = "Datasources", description = "Datasource management endpoints")
@RequiredArgsConstructor
@Slf4j
class DatasourceController {

    private final DatasourceAdminService datasourceAdminService;
    private final AuditLogService auditLogService;
    private final DriverCatalogService driverCatalogService;

    @GetMapping("/types")
    @Operation(summary = "List supported database types with driver resolution status")
    @ApiResponse(responseCode = "200", description = "Catalog of database types")
    DatasourceTypesResponse listTypes() {
        return DatasourceTypesResponse.from(driverCatalogService.list());
    }

    @GetMapping
    @Operation(summary = "List datasources accessible to the caller (paginated)")
    @ApiResponse(responseCode = "200", description = "Page of datasources")
    DatasourcePageResponse listDatasources(Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = isAdmin(caller)
                ? datasourceAdminService.listForAdmin(caller.organizationId(), pageable)
                : datasourceAdminService.listForUser(caller.organizationId(), caller.userId(),
                        pageable);
        return DatasourcePageResponse.from(page.map(DatasourceResponse::from));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new datasource")
    @ApiResponse(responseCode = "201", description = "Datasource created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "A datasource with this name already exists")
    ResponseEntity<DatasourceResponse> createDatasource(
            @Valid @RequestBody CreateDatasourceRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        var caller = currentClaims(authentication);
        var command = new CreateDatasourceCommand(
                caller.organizationId(),
                request.name(),
                request.dbType(),
                request.host(),
                request.port(),
                request.databaseName(),
                request.username(),
                request.password(),
                request.sslMode(),
                request.connectionPoolSize(),
                request.maxRowsPerQuery(),
                request.requireReviewReads(),
                request.requireReviewWrites(),
                request.reviewPlanId(),
                request.aiAnalysisEnabled());
        var created = datasourceAdminService.create(command);
        recordAudit(AuditAction.DATASOURCE_CREATED, AuditResourceType.DATASOURCE, created.id(),
                caller, httpRequest, Map.of("name", created.name(), "db_type", created.dbType().name()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(DatasourceResponse.from(created));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a datasource by id")
    @ApiResponse(responseCode = "200", description = "Datasource details")
    @ApiResponse(responseCode = "404", description = "Datasource not found or not accessible")
    DatasourceResponse getDatasource(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        var view = isAdmin(caller)
                ? datasourceAdminService.getForAdmin(id, caller.organizationId())
                : datasourceAdminService.getForUser(id, caller.organizationId(), caller.userId());
        return DatasourceResponse.from(view);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update an existing datasource")
    @ApiResponse(responseCode = "200", description = "Datasource updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    @ApiResponse(responseCode = "409", description = "Name conflict with another datasource")
    DatasourceResponse updateDatasource(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateDatasourceRequest request,
                                        Authentication authentication,
                                        HttpServletRequest httpRequest) {
        var caller = currentClaims(authentication);
        var command = new UpdateDatasourceCommand(
                request.name(),
                request.host(),
                request.port(),
                request.databaseName(),
                request.username(),
                request.password(),
                request.sslMode(),
                request.connectionPoolSize(),
                request.maxRowsPerQuery(),
                request.requireReviewReads(),
                request.requireReviewWrites(),
                request.reviewPlanId(),
                request.aiAnalysisEnabled(),
                request.active());
        var updated = datasourceAdminService.update(id, caller.organizationId(), command);
        recordAudit(AuditAction.DATASOURCE_UPDATED, AuditResourceType.DATASOURCE, id, caller,
                httpRequest, Map.of("name", updated.name()));
        return DatasourceResponse.from(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Soft-delete a datasource (sets active=false)")
    @ApiResponse(responseCode = "204", description = "Datasource deactivated")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    ResponseEntity<Void> deactivateDatasource(@PathVariable UUID id,
                                              Authentication authentication) {
        var caller = currentClaims(authentication);
        datasourceAdminService.deactivate(id, caller.organizationId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Test connectivity to the customer database")
    @ApiResponse(responseCode = "200", description = "Connection succeeded")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    @ApiResponse(responseCode = "422", description = "Connection failed")
    ConnectionTestResponse testConnection(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return ConnectionTestResponse.from(
                datasourceAdminService.test(id, caller.organizationId()));
    }

    @GetMapping("/{id}/schema")
    @Operation(summary = "Introspect tables and columns from the customer database")
    @ApiResponse(responseCode = "200", description = "Database schema")
    @ApiResponse(responseCode = "404", description = "Datasource not found or not accessible")
    @ApiResponse(responseCode = "422", description = "Schema introspection failed")
    DatabaseSchemaResponse getSchema(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return DatabaseSchemaResponse.from(datasourceAdminService.introspectSchema(
                id, caller.organizationId(), caller.userId(), isAdmin(caller)));
    }

    @GetMapping("/{id}/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List user permissions on a datasource")
    @ApiResponse(responseCode = "200", description = "List of permissions")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    PermissionListResponse listPermissions(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        var permissions = datasourceAdminService.listPermissions(id, caller.organizationId())
                .stream()
                .map(PermissionResponse::from)
                .toList();
        return new PermissionListResponse(permissions);
    }

    @PostMapping("/{id}/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Grant a user access to a datasource")
    @ApiResponse(responseCode = "201", description = "Permission granted")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    @ApiResponse(responseCode = "409", description = "Permission already exists for this user")
    @ApiResponse(responseCode = "422", description = "Target user is not in the organization")
    ResponseEntity<PermissionResponse> grantPermission(
            @PathVariable UUID id,
            @Valid @RequestBody CreatePermissionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        var caller = currentClaims(authentication);
        var command = new CreatePermissionCommand(
                request.userId(),
                request.canRead(),
                request.canWrite(),
                request.canDdl(),
                request.rowLimitOverride(),
                request.allowedSchemas(),
                request.allowedTables(),
                request.expiresAt());
        var view = datasourceAdminService.grantPermission(id, caller.organizationId(),
                caller.userId(), command);
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", id.toString());
        metadata.put("user_id", view.userId().toString());
        metadata.put("can_read", view.canRead());
        metadata.put("can_write", view.canWrite());
        metadata.put("can_ddl", view.canDdl());
        recordAudit(AuditAction.PERMISSION_GRANTED, AuditResourceType.PERMISSION, view.id(),
                caller, httpRequest, metadata);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{permId}")
                .buildAndExpand(view.id())
                .toUri();
        return ResponseEntity.created(location).body(PermissionResponse.from(view));
    }

    @DeleteMapping("/{id}/permissions/{permId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Revoke a user's permission on a datasource")
    @ApiResponse(responseCode = "204", description = "Permission revoked")
    @ApiResponse(responseCode = "404", description = "Permission not found")
    ResponseEntity<Void> revokePermission(@PathVariable UUID id,
                                          @PathVariable UUID permId,
                                          Authentication authentication,
                                          HttpServletRequest httpRequest) {
        var caller = currentClaims(authentication);
        datasourceAdminService.revokePermission(id, caller.organizationId(), permId);
        recordAudit(AuditAction.PERMISSION_REVOKED, AuditResourceType.PERMISSION, permId, caller,
                httpRequest, Map.of("datasource_id", id.toString()));
        return ResponseEntity.noContent().build();
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private boolean isAdmin(JwtClaims claims) {
        return claims.role() == UserRoleType.ADMIN;
    }

    private void recordAudit(AuditAction action, AuditResourceType resourceType, UUID resourceId,
                             JwtClaims caller, HttpServletRequest httpRequest,
                             Map<String, Object> metadata) {
        try {
            var context = RequestAuditContext.from(httpRequest);
            auditLogService.record(new AuditEntry(
                    action,
                    resourceType,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {} {}", action, resourceType.dbValue(),
                    resourceId, ex);
        }
    }
}
