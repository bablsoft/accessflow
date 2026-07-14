package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationAdminService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/api-connectors/{connectorId}/classification-tags")
@Tag(name = "API connector classification tags",
        description = "Per-connector data-classification tags with masking derivation (AF-518)")
@PreAuthorize("hasAuthority('PERM_API_CONNECTOR_MANAGE')")
@RequiredArgsConstructor
class ApiConnectorClassificationController {

    private final ApiConnectorClassificationAdminService service;
    private final ApiGovAuditWriter auditWriter;

    @GetMapping
    @Operation(summary = "List data-classification tags on an API connector")
    @ApiResponse(responseCode = "200", description = "List of classification tags")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    ApiClassificationTagListResponse list(@PathVariable UUID connectorId, Authentication authentication) {
        var caller = claims(authentication);
        var tags = service.listForConnector(connectorId, caller.organizationId()).stream()
                .map(ApiClassificationTagResponse::from)
                .toList();
        return new ApiClassificationTagListResponse(tags);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tag an API-connector response field with one or more data classifications")
    @ApiResponse(responseCode = "201", description = "Classification tags created")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    @ApiResponse(responseCode = "422", description = "Invalid matcher, duplicate tag, or no classification")
    ApiClassificationTagListResponse create(@PathVariable UUID connectorId,
                                            @Valid @RequestBody CreateApiClassificationTagRequest body,
                                            Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var created = service.create(connectorId, caller.organizationId(), body.toCommand());
        var metadata = new HashMap<String, Object>();
        metadata.put("field_ref", body.fieldRef());
        metadata.put("matcher_type", body.matcherType().name());
        metadata.put("count", created.size());
        auditWriter.record(AuditAction.API_CONNECTOR_CLASSIFICATION_TAG_ADDED,
                AuditResourceType.API_CONNECTOR, connectorId, caller, metadata, auditContext);
        return new ApiClassificationTagListResponse(
                created.stream().map(ApiClassificationTagResponse::from).toList());
    }

    @DeleteMapping("/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a data-classification tag")
    @ApiResponse(responseCode = "204", description = "Classification tag removed")
    @ApiResponse(responseCode = "404", description = "Connector or tag not found")
    void delete(@PathVariable UUID connectorId, @PathVariable UUID tagId,
                Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        service.delete(tagId, connectorId, caller.organizationId());
        var metadata = new HashMap<String, Object>();
        metadata.put("tag_id", tagId.toString());
        auditWriter.record(AuditAction.API_CONNECTOR_CLASSIFICATION_TAG_REMOVED,
                AuditResourceType.API_CONNECTOR, connectorId, caller, metadata, auditContext);
    }

    @GetMapping("/derivation-preview")
    @Operation(summary = "Preview the stricter handling derived from a connector's classification tags")
    @ApiResponse(responseCode = "200", description = "Derivation preview")
    @ApiResponse(responseCode = "404", description = "Connector not found")
    ApiClassificationDerivationResponse derivationPreview(@PathVariable UUID connectorId,
                                                          Authentication authentication) {
        var caller = claims(authentication);
        return ApiClassificationDerivationResponse.from(
                service.previewDerivation(connectorId, caller.organizationId()));
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
