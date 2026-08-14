package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.scim.internal.protocol.ScimSchemas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * SCIM 2.0 discovery endpoints (#621). IdPs call these once during integration setup to learn
 * the server's capabilities: PATCH is supported; bulk, sorting, ETags, and password changes are
 * not; filtering is capped at 200 results.
 */
@RestController
@RequestMapping(path = "/scim/v2", produces = {ScimMediaTypes.SCIM_JSON_VALUE, "application/json"})
@Tag(name = "SCIM Discovery", description = "SCIM 2.0 capability and schema discovery")
class ScimDiscoveryController {

    @GetMapping("/ServiceProviderConfig")
    @Operation(summary = "SCIM capability discovery")
    @ApiResponse(responseCode = "200", description = "Service provider configuration")
    Map<String, Object> serviceProviderConfig() {
        return Map.of(
                "schemas", List.of(ScimSchemas.SERVICE_PROVIDER_CONFIG),
                "documentationUri", "https://accessflow.dev/docs/configuration/auth/#cfg-scim",
                "patch", Map.of("supported", true),
                "bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0),
                "filter", Map.of("supported", true, "maxResults", 200),
                "changePassword", Map.of("supported", false),
                "sort", Map.of("supported", false),
                "etag", Map.of("supported", false),
                "authenticationSchemes", List.of(Map.of(
                        "type", "oauthbearertoken",
                        "name", "Bearer token",
                        "description", "Long-lived per-organization bearer token issued by an "
                                + "AccessFlow admin")));
    }

    @GetMapping("/ResourceTypes")
    @Operation(summary = "SCIM resource-type discovery")
    @ApiResponse(responseCode = "200", description = "Supported resource types")
    List<Map<String, Object>> resourceTypes() {
        var base = baseUrl();
        return List.of(
                Map.of(
                        "schemas", List.of(ScimSchemas.RESOURCE_TYPE),
                        "id", "User",
                        "name", "User",
                        "endpoint", "/Users",
                        "schema", ScimSchemas.USER,
                        "meta", Map.of("resourceType", "ResourceType",
                                "location", base + "/ResourceTypes/User")),
                Map.of(
                        "schemas", List.of(ScimSchemas.RESOURCE_TYPE),
                        "id", "Group",
                        "name", "Group",
                        "endpoint", "/Groups",
                        "schema", ScimSchemas.GROUP,
                        "meta", Map.of("resourceType", "ResourceType",
                                "location", base + "/ResourceTypes/Group")));
    }

    @GetMapping("/Schemas")
    @Operation(summary = "SCIM schema discovery (minimal descriptors)")
    @ApiResponse(responseCode = "200", description = "Supported schemas")
    List<Map<String, Object>> schemas() {
        return List.of(
                Map.of(
                        "id", ScimSchemas.USER,
                        "name", "User",
                        "description", "AccessFlow user (SCIM-owned attributes: userName, "
                                + "displayName, name.formatted, emails, externalId, active)"),
                Map.of(
                        "id", ScimSchemas.GROUP,
                        "name", "Group",
                        "description", "AccessFlow user group (displayName, externalId, members)"));
    }

    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path("/scim/v2").toUriString();
    }
}
