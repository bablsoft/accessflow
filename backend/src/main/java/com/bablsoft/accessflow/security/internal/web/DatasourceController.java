package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.CreateDatasourceCommand;
import com.bablsoft.accessflow.core.api.CreateDatasourceReviewerCommand;
import com.bablsoft.accessflow.core.api.CreatePermissionCommand;
import com.bablsoft.accessflow.core.api.CustomJdbcDriverService;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceReviewerService;
import com.bablsoft.accessflow.core.api.DriverCatalogService;
import com.bablsoft.accessflow.core.api.IllegalDatasourceReviewerException;
import com.bablsoft.accessflow.core.api.TestReplicaCommand;
import com.bablsoft.accessflow.core.api.UpdateDatasourceCommand;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.proxy.api.SampleDataService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.ConnectionTestResponse;
import com.bablsoft.accessflow.security.internal.web.model.CreateDatasourceRequest;
import com.bablsoft.accessflow.security.internal.web.model.CreateDatasourceReviewerRequest;
import com.bablsoft.accessflow.security.internal.web.model.CreatePermissionRequest;
import com.bablsoft.accessflow.security.internal.web.model.DatabaseSchemaResponse;
import com.bablsoft.accessflow.security.internal.web.model.DatasourcePageResponse;
import com.bablsoft.accessflow.security.internal.web.model.DatasourceResponse;
import com.bablsoft.accessflow.security.internal.web.model.DatasourceReviewerListResponse;
import com.bablsoft.accessflow.security.internal.web.model.DatasourceReviewerResponse;
import com.bablsoft.accessflow.security.internal.web.model.DatasourceTypesResponse;
import com.bablsoft.accessflow.security.internal.web.model.PermissionListResponse;
import com.bablsoft.accessflow.security.internal.web.model.PermissionResponse;
import com.bablsoft.accessflow.security.internal.web.model.TestReplicaRequest;
import com.bablsoft.accessflow.security.internal.web.model.SampleRowsResponse;
import com.bablsoft.accessflow.security.internal.web.model.UpdateDatasourceRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
@Validated
@Slf4j
class DatasourceController {

    private final DatasourceAdminService datasourceAdminService;
    private final AuditLogService auditLogService;
    private final DriverCatalogService driverCatalogService;
    private final CustomJdbcDriverService customJdbcDriverService;
    private final DatasourceReviewerService datasourceReviewerService;
    private final SampleDataService sampleDataService;

    @GetMapping("/types")
    @Operation(summary = "List supported database types with driver resolution status")
    @ApiResponse(responseCode = "200", description = "Catalog of database types")
    DatasourceTypesResponse listTypes(Authentication authentication) {
        var caller = currentClaims(authentication);
        var orgDrivers = customJdbcDriverService.list(caller.organizationId()).stream()
                .map(view -> new com.bablsoft.accessflow.core.api.CustomDriverDescriptor(
                        view.id(),
                        view.organizationId(),
                        view.targetDbType(),
                        view.vendorName(),
                        view.driverClass(),
                        view.jarFilename(),
                        view.jarSha256(),
                        view.jarSizeBytes(),
                        ""))
                .toList();
        return DatasourceTypesResponse.from(
                driverCatalogService.list(caller.organizationId(), orgDrivers));
    }

    @GetMapping
    @Operation(summary = "List datasources accessible to the caller (paginated)")
    @ApiResponse(responseCode = "200", description = "Page of datasources")
    DatasourcePageResponse listDatasources(Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var pageRequest = SpringPageableAdapter.toPageRequest(pageable);
        var page = isAdmin(caller)
                ? datasourceAdminService.listForAdmin(caller.organizationId(), pageRequest)
                : datasourceAdminService.listForUser(caller.organizationId(), caller.userId(),
                        pageRequest);
        return DatasourcePageResponse.from(page.map(DatasourceResponse::from));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new datasource")
    @ApiResponse(responseCode = "201", description = "Datasource created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "A datasource with this name already exists")
    @ApiResponse(responseCode = "422", description = "JDBC driver for the selected db_type cannot be resolved")
    ResponseEntity<DatasourceResponse> createDatasource(
            @Valid @RequestBody CreateDatasourceRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
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
                request.aiAnalysisEnabled(),
                request.aiConfigId(),
                request.textToSqlEnabled(),
                request.customDriverId(),
                request.connectorId(),
                request.jdbcUrlOverride(),
                request.readReplicaJdbcUrl(),
                request.readReplicaUsername(),
                request.readReplicaPassword(),
                request.localDatacenter(),
                request.apiKey());
        var created = datasourceAdminService.create(command);
        recordAudit(AuditAction.DATASOURCE_CREATED, AuditResourceType.DATASOURCE, created.id(),
                caller, auditContext, Map.of("name", created.name(), "db_type", created.dbType().name()));
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
                                        RequestAuditContext auditContext) {
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
                request.aiConfigId(),
                request.textToSqlEnabled(),
                request.clearAiConfig(),
                request.jdbcUrlOverride(),
                request.readReplicaJdbcUrl(),
                request.readReplicaUsername(),
                request.readReplicaPassword(),
                request.active(),
                request.localDatacenter(),
                request.apiKey());
        var updated = datasourceAdminService.update(id, caller.organizationId(), command);
        recordAudit(AuditAction.DATASOURCE_UPDATED, AuditResourceType.DATASOURCE, id, caller,
                auditContext, Map.of("name", updated.name()));
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

    @PostMapping("/{id}/test-replica")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Test connectivity to a candidate read-replica using live values")
    @ApiResponse(responseCode = "200", description = "Replica connection succeeded")
    @ApiResponse(responseCode = "400", description = "Invalid replica URL or no persisted password to fall back on")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    @ApiResponse(responseCode = "422", description = "Replica connection failed")
    ConnectionTestResponse testReplicaConnection(@PathVariable UUID id,
                                                 @Valid @RequestBody TestReplicaRequest request,
                                                 Authentication authentication) {
        var caller = currentClaims(authentication);
        var command = new TestReplicaCommand(request.jdbcUrl(), request.username(),
                request.password());
        return ConnectionTestResponse.from(
                datasourceAdminService.testReplica(id, caller.organizationId(), command));
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

    @GetMapping("/{id}/sample-rows")
    @Operation(summary = "Read a bounded, row-level-security- and masking-aware sample of rows "
            + "from a table")
    @ApiResponse(responseCode = "200", description = "Sample rows (masked columns carry the "
            + "masked value, never the raw one)")
    @ApiResponse(responseCode = "404", description = "Datasource or table not found or not accessible")
    @ApiResponse(responseCode = "422", description = "Sampling failed (customer database unreachable)")
    SampleRowsResponse sampleRows(@PathVariable UUID id,
                                  @RequestParam(required = false) String schema,
                                  @RequestParam String table,
                                  @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
                                  Authentication authentication) {
        var caller = currentClaims(authentication);
        return SampleRowsResponse.from(sampleDataService.sample(
                id, caller.organizationId(), caller.userId(), isAdmin(caller), schema, table, limit));
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
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new CreatePermissionCommand(
                request.userId(),
                request.canRead(),
                request.canWrite(),
                request.canDdl(),
                request.rowLimitOverride(),
                request.allowedSchemas(),
                request.allowedTables(),
                request.restrictedColumns(),
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
                caller, auditContext, metadata);
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
                                          RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        datasourceAdminService.revokePermission(id, caller.organizationId(), permId);
        recordAudit(AuditAction.PERMISSION_REVOKED, AuditResourceType.PERMISSION, permId, caller,
                auditContext, Map.of("datasource_id", id.toString()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/reviewers")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List per-datasource reviewer assignments (users and groups)")
    @ApiResponse(responseCode = "200", description = "List of reviewers; empty means fall back to plan approvers")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    DatasourceReviewerListResponse listReviewers(@PathVariable UUID id,
                                                 Authentication authentication) {
        var caller = currentClaims(authentication);
        var reviewers = datasourceReviewerService
                .listForDatasource(id, caller.organizationId()).stream()
                .map(DatasourceReviewerResponse::from)
                .toList();
        return new DatasourceReviewerListResponse(reviewers);
    }

    @PostMapping("/{id}/reviewers")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add a user or group as a reviewer of this datasource")
    @ApiResponse(responseCode = "201", description = "Reviewer added")
    @ApiResponse(responseCode = "404", description = "Datasource, user, or group not found")
    @ApiResponse(responseCode = "409", description = "Reviewer already exists")
    @ApiResponse(responseCode = "422", description = "Must specify exactly one of userId or groupId")
    ResponseEntity<DatasourceReviewerResponse> addReviewer(
            @PathVariable UUID id,
            @RequestBody CreateDatasourceReviewerRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        if (request == null || (request.userId() == null) == (request.groupId() == null)) {
            throw new IllegalDatasourceReviewerException(
                    "Exactly one of userId or groupId must be provided");
        }
        var command = new CreateDatasourceReviewerCommand(id, caller.organizationId(),
                caller.userId(), request.userId(), request.groupId());
        var view = datasourceReviewerService.add(command);
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", id.toString());
        if (view.userId() != null) {
            metadata.put("user_id", view.userId().toString());
        }
        if (view.groupId() != null) {
            metadata.put("group_id", view.groupId().toString());
        }
        recordAudit(AuditAction.DATASOURCE_REVIEWER_ADDED, AuditResourceType.DATASOURCE_REVIEWER,
                view.id(), caller, auditContext, metadata);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{reviewerId}")
                .buildAndExpand(view.id())
                .toUri();
        return ResponseEntity.created(location).body(DatasourceReviewerResponse.from(view));
    }

    @DeleteMapping("/{id}/reviewers/{reviewerId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remove a datasource reviewer assignment")
    @ApiResponse(responseCode = "204", description = "Reviewer removed")
    @ApiResponse(responseCode = "404", description = "Datasource or reviewer not found")
    ResponseEntity<Void> removeReviewer(@PathVariable UUID id, @PathVariable UUID reviewerId,
                                        Authentication authentication,
                                        RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        datasourceReviewerService.remove(reviewerId, id, caller.organizationId());
        recordAudit(AuditAction.DATASOURCE_REVIEWER_REMOVED, AuditResourceType.DATASOURCE_REVIEWER,
                reviewerId, caller, auditContext, Map.of("datasource_id", id.toString()));
        return ResponseEntity.noContent().build();
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private boolean isAdmin(JwtClaims claims) {
        return claims.role() == UserRoleType.ADMIN;
    }

    private void recordAudit(AuditAction action, AuditResourceType resourceType, UUID resourceId,
                             JwtClaims caller, RequestAuditContext auditContext,
                             Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    resourceType,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {} {}", action, resourceType.dbValue(),
                    resourceId, ex);
        }
    }
}
