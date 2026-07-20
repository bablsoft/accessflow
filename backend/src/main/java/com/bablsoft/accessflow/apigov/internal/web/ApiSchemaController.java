package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.apigov.api.ApiSchemaView;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/api-connectors/{connectorId}")
@Tag(name = "API Schemas", description = "Upload and explore API connector schemas")
@RequiredArgsConstructor
class ApiSchemaController {

    private final ApiSchemaService schemaService;
    private final ApiConnectorAdminService connectorService;
    private final ApiGovAuditWriter auditWriter;

    @PostMapping("/schemas")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_API_CONNECTOR_MANAGE')")
    @Operation(summary = "Upload and parse a schema for a connector")
    @ApiResponse(responseCode = "201", description = "Schema uploaded and parsed")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    @ApiResponse(responseCode = "422", description = "Schema could not be parsed")
    ApiSchemaResponse upload(@PathVariable UUID connectorId,
                             @Valid @RequestBody UploadApiSchemaRequest body,
                             Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var view = schemaService.upload(connectorId, caller.organizationId(), body.schemaType(),
                body.rawContent(), body.sourceUrl(), body.toFilter());
        auditFilterChange(connectorId, caller, view, "uploaded", auditContext);
        return ApiSchemaResponse.from(view);
    }

    @PostMapping("/schemas/preview")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAuthority('PERM_API_CONNECTOR_MANAGE')")
    @Operation(summary = "Dry-run an operation filter against a schema without persisting it")
    @ApiResponse(responseCode = "200", description = "Kept/excluded counts and the dropped operations")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    @ApiResponse(responseCode = "422", description = "Schema could not be parsed")
    OperationFilterPreviewResponse preview(@PathVariable UUID connectorId,
                                           @Valid @RequestBody UploadApiSchemaRequest body,
                                           Authentication authentication) {
        var caller = claims(authentication);
        var preview = schemaService.previewFilter(connectorId, caller.organizationId(), body.schemaType(),
                body.rawContent(), body.sourceUrl(), body.toFilter());
        return OperationFilterPreviewResponse.from(preview);
    }

    @PutMapping("/schemas/{schemaId}/filter")
    @PreAuthorize("hasAuthority('PERM_API_CONNECTOR_MANAGE')")
    @Operation(summary = "Re-edit a schema's operation filter without re-uploading the document")
    @ApiResponse(responseCode = "200", description = "Filter updated")
    @ApiResponse(responseCode = "404", description = "Connector or schema not found")
    ApiSchemaResponse updateFilter(@PathVariable UUID connectorId, @PathVariable UUID schemaId,
                                   @Valid @RequestBody OperationFilterRequest body,
                                   Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var view = schemaService.updateFilter(connectorId, caller.organizationId(), schemaId,
                body.toDomain());
        auditFilterChange(connectorId, caller, view, "filter_updated", auditContext);
        return ApiSchemaResponse.from(view);
    }

    @GetMapping("/schemas")
    @Operation(summary = "List a connector's uploaded schemas")
    @ApiResponse(responseCode = "200", description = "Schema list")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    List<ApiSchemaResponse> list(@PathVariable UUID connectorId, Authentication authentication) {
        var caller = claims(authentication);
        return schemaService.list(connectorId, caller.organizationId()).stream()
                .map(ApiSchemaResponse::from)
                .toList();
    }

    @DeleteMapping("/schemas/{schemaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_API_CONNECTOR_MANAGE')")
    @Operation(summary = "Delete a connector schema")
    @ApiResponse(responseCode = "204", description = "Schema deleted")
    @ApiResponse(responseCode = "404", description = "Connector or schema not found")
    void delete(@PathVariable UUID connectorId, @PathVariable UUID schemaId,
                Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        schemaService.delete(connectorId, caller.organizationId(), schemaId);
        auditWriter.record(AuditAction.API_SCHEMA_DELETED, AuditResourceType.API_CONNECTOR,
                connectorId, caller, new HashMap<>(), auditContext);
    }

    @GetMapping("/operations")
    @Operation(summary = "List the normalized operation catalog from a connector's latest schema")
    @ApiResponse(responseCode = "200", description = "Operation catalog")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    List<ApiOperationResponse> operations(@PathVariable UUID connectorId, Authentication authentication) {
        var caller = claims(authentication);
        // Visibility check: non-admins must hold an active permission on the connector.
        if (!isAdmin(caller)) {
            connectorService.getForUser(connectorId, caller.organizationId(), caller.userId());
        }
        return schemaService.listOperations(connectorId, caller.organizationId()).stream()
                .map(ApiOperationResponse::from)
                .toList();
    }

    private void auditFilterChange(UUID connectorId, JwtClaims caller, ApiSchemaView view,
                                   String action, RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("action", action);
        metadata.put("schema_type", view.schemaType().name());
        metadata.put("operation_count", view.operationCount());
        metadata.put("total_operation_count", view.totalOperationCount());
        metadata.put("excluded_count", view.totalOperationCount() - view.operationCount());
        var filter = OperationFilterResponse.from(view.operationFilter());
        if (filter != null) {
            metadata.put("filter", filter);
        }
        auditWriter.record(AuditAction.API_SCHEMA_UPLOADED, AuditResourceType.API_CONNECTOR,
                connectorId, caller, metadata, auditContext);
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private static boolean isAdmin(JwtClaims caller) {
        return caller.has(Permission.QUERY_ADMIN);
    }
}
