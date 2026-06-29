package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
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
@RequestMapping("/api/v1/api-connectors")
@Tag(name = "API Connectors", description = "Register and govern outbound API targets")
@RequiredArgsConstructor
class ApiConnectorController {

    private final ApiConnectorAdminService service;
    private final ApiGovAuditWriter auditWriter;

    @GetMapping
    @Operation(summary = "List API connectors (admins see all; others see granted connectors)")
    @ApiResponse(responseCode = "200", description = "Page of connectors")
    ApiConnectorPageResponse list(Authentication authentication, Pageable pageable) {
        var caller = claims(authentication);
        var pageRequest = SpringPageableAdapter.toPageRequest(pageable);
        var page = isAdmin(caller)
                ? service.listForAdmin(caller.organizationId(), pageRequest)
                : service.listForUser(caller.organizationId(), caller.userId(), pageRequest);
        return ApiConnectorPageResponse.from(page);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an API connector")
    @ApiResponse(responseCode = "200", description = "Connector")
    @ApiResponse(responseCode = "404", description = "Connector not found or not accessible")
    ApiConnectorResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        var view = isAdmin(caller)
                ? service.getForAdmin(id, caller.organizationId())
                : service.getForUser(id, caller.organizationId(), caller.userId());
        return ApiConnectorResponse.from(view);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create an API connector "
            + "(OAuth2 token-sourcing fields apply when auth_method=OAUTH2_CLIENT_CREDENTIALS; "
            + "secrets are write-only and never returned)")
    @ApiResponse(responseCode = "201", description = "Connector created")
    @ApiResponse(responseCode = "409", description = "A connector with this name already exists")
    ApiConnectorResponse create(@Valid @RequestBody CreateApiConnectorRequest body,
                                Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var view = service.create(body.toCommand(caller.organizationId()));
        auditWriter.record(AuditAction.API_CONNECTOR_CREATED, AuditResourceType.API_CONNECTOR,
                view.id(), caller, metadata("name", view.name(), "protocol", view.protocol().name()),
                auditContext);
        return ApiConnectorResponse.from(view);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update an API connector "
            + "(omit an OAuth2 secret field to leave the stored value unchanged; "
            + "changing OAuth2 config evicts any cached access token)")
    @ApiResponse(responseCode = "200", description = "Connector updated")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    @ApiResponse(responseCode = "409", description = "A connector with this name already exists")
    ApiConnectorResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateApiConnectorRequest body,
                                Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var view = service.update(id, caller.organizationId(), body.toCommand());
        auditWriter.record(AuditAction.API_CONNECTOR_UPDATED, AuditResourceType.API_CONNECTOR,
                id, caller, metadata("name", view.name()), auditContext);
        return ApiConnectorResponse.from(view);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete an API connector")
    @ApiResponse(responseCode = "204", description = "Connector deleted")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    void delete(@PathVariable UUID id, Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        service.delete(id, caller.organizationId());
        auditWriter.record(AuditAction.API_CONNECTOR_DELETED, AuditResourceType.API_CONNECTOR,
                id, caller, new HashMap<>(), auditContext);
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Probe connectivity to the connector's base URL "
            + "(for OAUTH2_CLIENT_CREDENTIALS connectors this also exercises the token fetch)")
    @ApiResponse(responseCode = "200", description = "Probe result")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    ApiConnectionTestResponse test(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return ApiConnectionTestResponse.from(service.test(id, caller.organizationId()));
    }

    @GetMapping("/{id}/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List per-user permissions on a connector")
    @ApiResponse(responseCode = "200", description = "Permission list")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    List<ApiConnectorPermissionResponse> listPermissions(@PathVariable UUID id,
                                                         Authentication authentication) {
        var caller = claims(authentication);
        return service.listPermissions(id, caller.organizationId()).stream()
                .map(ApiConnectorPermissionResponse::from)
                .toList();
    }

    @PostMapping("/{id}/permissions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Grant or update a user's access to a connector")
    @ApiResponse(responseCode = "201", description = "Permission granted")
    @ApiResponse(responseCode = "404", description = "Connector or user not found")
    ApiConnectorPermissionResponse grant(@PathVariable UUID id,
                                         @Valid @RequestBody GrantApiConnectorPermissionRequest body,
                                         Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var view = service.grantPermission(id, caller.organizationId(), caller.userId(), body.toCommand());
        auditWriter.record(AuditAction.API_PERMISSION_GRANTED, AuditResourceType.API_CONNECTOR,
                id, caller, metadata("user_id", body.userId().toString()), auditContext);
        return ApiConnectorPermissionResponse.from(view);
    }

    @DeleteMapping("/{id}/permissions/{permissionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Revoke a user's access to a connector")
    @ApiResponse(responseCode = "204", description = "Permission revoked")
    @ApiResponse(responseCode = "404", description = "Connector or permission not found")
    void revoke(@PathVariable UUID id, @PathVariable UUID permissionId, Authentication authentication,
                RequestAuditContext auditContext) {
        var caller = claims(authentication);
        service.revokePermission(id, caller.organizationId(), permissionId);
        auditWriter.record(AuditAction.API_PERMISSION_REVOKED, AuditResourceType.API_CONNECTOR,
                id, caller, metadata("permission_id", permissionId.toString()), auditContext);
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private static boolean isAdmin(JwtClaims caller) {
        return caller.role() != null && "ADMIN".equals(caller.role().name());
    }

    private static java.util.Map<String, Object> metadata(String... kv) {
        var map = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}
