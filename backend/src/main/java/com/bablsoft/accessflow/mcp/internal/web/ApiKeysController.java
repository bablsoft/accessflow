package com.bablsoft.accessflow.mcp.internal.web;

import com.bablsoft.accessflow.mcp.api.ApiKeyService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/api-keys")
@Tag(name = "API Keys", description = "User-managed API keys for MCP and programmatic access")
@RequiredArgsConstructor
class ApiKeysController {

    private final ApiKeyService apiKeyService;

    @GetMapping
    @Operation(summary = "List the caller's API keys")
    @ApiResponse(responseCode = "200", description = "API keys returned")
    List<ApiKeyResponse> list(Authentication authentication) {
        var claims = (JwtClaims) authentication.getPrincipal();
        return apiKeyService.list(claims.userId()).stream()
                .map(ApiKeyResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new API key — the raw key is returned once and never again")
    @ApiResponse(responseCode = "201", description = "API key created — rawKey returned")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "An API key with that name already exists")
    ApiKeyCreateResponse create(@Valid @RequestBody ApiKeyCreateRequest request,
                                Authentication authentication) {
        var claims = (JwtClaims) authentication.getPrincipal();
        var issued = apiKeyService.issue(claims.userId(), claims.organizationId(),
                request.name(), request.expiresAt());
        return ApiKeyCreateResponse.from(issued);
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke an API key (idempotent)")
    @ApiResponse(responseCode = "204", description = "API key revoked")
    @ApiResponse(responseCode = "404", description = "API key not found or not owned by caller")
    void revoke(@PathVariable UUID keyId, Authentication authentication) {
        var claims = (JwtClaims) authentication.getPrincipal();
        apiKeyService.revoke(claims.userId(), keyId);
    }
}
