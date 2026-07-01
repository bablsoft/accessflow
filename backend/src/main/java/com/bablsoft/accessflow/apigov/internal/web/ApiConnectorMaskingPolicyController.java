package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingPolicyView;
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
@RequestMapping("/api/v1/api-connectors/{connectorId}/masking-policies")
@Tag(name = "API connector masking policies",
        description = "Per-connector response-masking policies with conditional reveal (AF-518)")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
class ApiConnectorMaskingPolicyController {

    private final ApiConnectorMaskingAdminService service;
    private final ApiGovAuditWriter auditWriter;

    @GetMapping
    @Operation(summary = "List response-masking policies configured on an API connector")
    @ApiResponse(responseCode = "200", description = "List of masking policies")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    ApiMaskingPolicyListResponse list(@PathVariable UUID connectorId, Authentication authentication) {
        var caller = claims(authentication);
        var policies = service.listForConnector(connectorId, caller.organizationId()).stream()
                .map(ApiMaskingPolicyResponse::from)
                .toList();
        return new ApiMaskingPolicyListResponse(policies);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a response-masking policy on an API connector")
    @ApiResponse(responseCode = "201", description = "Masking policy created")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    @ApiResponse(responseCode = "422", description = "Invalid matcher, strategy params, or reveal target")
    ApiMaskingPolicyResponse create(@PathVariable UUID connectorId,
                                    @Valid @RequestBody CreateApiMaskingPolicyRequest body,
                                    Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var view = service.create(connectorId, caller.organizationId(), body.toCommand());
        auditWriter.record(AuditAction.API_CONNECTOR_MASKING_POLICY_CREATED,
                AuditResourceType.API_CONNECTOR, connectorId, caller, metadata(view), auditContext);
        return ApiMaskingPolicyResponse.from(view);
    }

    @PutMapping("/{policyId}")
    @Operation(summary = "Update a response-masking policy")
    @ApiResponse(responseCode = "200", description = "Masking policy updated")
    @ApiResponse(responseCode = "404", description = "Connector or policy not found")
    @ApiResponse(responseCode = "422", description = "Invalid matcher, strategy params, or reveal target")
    ApiMaskingPolicyResponse update(@PathVariable UUID connectorId, @PathVariable UUID policyId,
                                    @Valid @RequestBody UpdateApiMaskingPolicyRequest body,
                                    Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var view = service.update(policyId, connectorId, caller.organizationId(), body.toCommand());
        auditWriter.record(AuditAction.API_CONNECTOR_MASKING_POLICY_UPDATED,
                AuditResourceType.API_CONNECTOR, connectorId, caller, metadata(view), auditContext);
        return ApiMaskingPolicyResponse.from(view);
    }

    @DeleteMapping("/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a response-masking policy")
    @ApiResponse(responseCode = "204", description = "Masking policy deleted")
    @ApiResponse(responseCode = "404", description = "Connector or policy not found")
    void delete(@PathVariable UUID connectorId, @PathVariable UUID policyId,
                Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        service.delete(policyId, connectorId, caller.organizationId());
        var metadata = new HashMap<String, Object>();
        metadata.put("policy_id", policyId.toString());
        auditWriter.record(AuditAction.API_CONNECTOR_MASKING_POLICY_DELETED,
                AuditResourceType.API_CONNECTOR, connectorId, caller, metadata, auditContext);
    }

    private static Map<String, Object> metadata(ApiConnectorMaskingPolicyView view) {
        var map = new HashMap<String, Object>();
        map.put("policy_id", view.id().toString());
        map.put("matcher_type", view.matcherType().name());
        map.put("field_ref", view.fieldRef());
        map.put("strategy", view.strategy().name());
        map.put("enabled", view.enabled());
        return map;
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
