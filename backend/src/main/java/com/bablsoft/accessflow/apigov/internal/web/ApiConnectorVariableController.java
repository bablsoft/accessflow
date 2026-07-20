package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableView;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/api-connectors/{connectorId}/variables")
@Tag(name = "API connector variables",
        description = "Per-connector dynamic variables — request signing, nonces, timestamps (AF-613)")
@PreAuthorize("hasAuthority('PERM_API_CONNECTOR_MANAGE')")
@RequiredArgsConstructor
class ApiConnectorVariableController {

    private final ApiConnectorVariableAdminService service;
    private final ApiGovAuditWriter auditWriter;

    @GetMapping
    @Operation(summary = "List the dynamic variables configured on an API connector")
    @ApiResponse(responseCode = "200", description = "List of variables, in evaluation order")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    ApiConnectorVariableListResponse list(@PathVariable UUID connectorId,
                                          Authentication authentication) {
        var caller = claims(authentication);
        var variables = service.listForConnector(connectorId, caller.organizationId()).stream()
                .map(ApiConnectorVariableResponse::from)
                .toList();
        return new ApiConnectorVariableListResponse(variables);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a dynamic variable on an API connector")
    @ApiResponse(responseCode = "201", description = "Variable created")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    @ApiResponse(responseCode = "422",
            description = "Invalid name, kind/field combination, target, or a dependency cycle")
    ApiConnectorVariableResponse create(@PathVariable UUID connectorId,
                                        @Valid @RequestBody CreateApiConnectorVariableRequest body,
                                        Authentication authentication,
                                        RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var view = service.create(connectorId, caller.organizationId(), body.toCommand());
        auditWriter.record(AuditAction.API_CONNECTOR_VARIABLE_CREATED,
                AuditResourceType.API_CONNECTOR, connectorId, caller, metadata(view), auditContext);
        return ApiConnectorVariableResponse.from(view);
    }

    @PutMapping("/{variableId}")
    @Operation(summary = "Update a dynamic variable")
    @ApiResponse(responseCode = "200", description = "Variable updated")
    @ApiResponse(responseCode = "404", description = "Connector or variable not found")
    @ApiResponse(responseCode = "422",
            description = "Invalid name, kind/field combination, target, or a dependency cycle")
    ApiConnectorVariableResponse update(@PathVariable UUID connectorId,
                                        @PathVariable UUID variableId,
                                        @Valid @RequestBody UpdateApiConnectorVariableRequest body,
                                        Authentication authentication,
                                        RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var view = service.update(variableId, connectorId, caller.organizationId(), body.toCommand());
        auditWriter.record(AuditAction.API_CONNECTOR_VARIABLE_UPDATED,
                AuditResourceType.API_CONNECTOR, connectorId, caller, metadata(view), auditContext);
        return ApiConnectorVariableResponse.from(view);
    }

    @DeleteMapping("/{variableId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a dynamic variable")
    @ApiResponse(responseCode = "204", description = "Variable deleted")
    @ApiResponse(responseCode = "404", description = "Connector or variable not found")
    @ApiResponse(responseCode = "422", description = "Another variable still references this one")
    void delete(@PathVariable UUID connectorId, @PathVariable UUID variableId,
                Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        service.delete(variableId, connectorId, caller.organizationId());
        var metadata = new HashMap<String, Object>();
        metadata.put("variable_id", variableId.toString());
        auditWriter.record(AuditAction.API_CONNECTOR_VARIABLE_DELETED,
                AuditResourceType.API_CONNECTOR, connectorId, caller, metadata, auditContext);
    }

    @PutMapping("/order")
    @Operation(summary = "Reassign the evaluation order of a connector's dynamic variables",
            description = "The body carries the connector's complete variable id list in the desired "
                    + "order. Evaluation order is observable for time- and randomness-dependent kinds.")
    @ApiResponse(responseCode = "200", description = "Variables reordered")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    @ApiResponse(responseCode = "422", description = "The id list is not the connector's complete set")
    ApiConnectorVariableListResponse reorder(
            @PathVariable UUID connectorId,
            @Valid @RequestBody ReorderApiConnectorVariablesRequest body,
            Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var views = service.reorder(connectorId, caller.organizationId(), body.toCommand());
        var metadata = new HashMap<String, Object>();
        metadata.put("variable_count", views.size());
        auditWriter.record(AuditAction.API_CONNECTOR_VARIABLES_REORDERED,
                AuditResourceType.API_CONNECTOR, connectorId, caller, metadata, auditContext);
        return new ApiConnectorVariableListResponse(
                views.stream().map(ApiConnectorVariableResponse::from).toList());
    }

    /** Audit metadata never carries the expression or the secret — only the shape of the change. */
    private static Map<String, Object> metadata(ApiConnectorVariableView view) {
        var map = new HashMap<String, Object>();
        map.put("variable_id", view.id().toString());
        map.put("name", view.name());
        map.put("kind", view.kind().name());
        map.put("has_secret", view.hasSecret());
        map.put("overridable", view.overridable());
        if (view.target() != null) {
            map.put("target", view.target());
        }
        return map;
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
