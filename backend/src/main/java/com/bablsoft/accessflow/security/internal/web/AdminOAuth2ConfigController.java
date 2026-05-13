package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.api.OAuth2ConfigService;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/oauth2-config")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin OAuth2 Config",
        description = "Per-organization OAuth2 / OIDC provider settings (ADMIN only)")
@RequiredArgsConstructor
class AdminOAuth2ConfigController {

    private final OAuth2ConfigService oauth2ConfigService;

    @GetMapping
    @Operation(summary = "List OAuth2 configuration for every supported provider")
    @ApiResponse(responseCode = "200", description = "One entry per supported provider (client secret masked)")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    List<OAuth2ConfigResponse> list(Authentication authentication) {
        var caller = currentClaims(authentication);
        return oauth2ConfigService.list(caller.organizationId()).stream()
                .map(OAuth2ConfigResponse::from)
                .toList();
    }

    @GetMapping("/{provider}")
    @Operation(summary = "Read the OAuth2 configuration for a single provider")
    @ApiResponse(responseCode = "200", description = "Provider configuration (client secret masked)")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    OAuth2ConfigResponse get(@PathVariable OAuth2ProviderType provider, Authentication authentication) {
        var caller = currentClaims(authentication);
        return OAuth2ConfigResponse.from(
                oauth2ConfigService.getOrDefault(caller.organizationId(), provider));
    }

    @PutMapping("/{provider}")
    @Operation(summary = "Create or update the OAuth2 configuration for a provider")
    @ApiResponse(responseCode = "200", description = "Updated configuration (client secret masked)")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @ApiResponse(responseCode = "422", description = "Configuration invalid (cannot activate without secret / tenant)")
    OAuth2ConfigResponse update(@PathVariable OAuth2ProviderType provider,
                                @Valid @RequestBody UpdateOAuth2ConfigRequest body,
                                Authentication authentication) {
        var caller = currentClaims(authentication);
        return OAuth2ConfigResponse.from(
                oauth2ConfigService.update(caller.organizationId(), provider, body.toCommand()));
    }

    @DeleteMapping("/{provider}")
    @Operation(summary = "Remove the OAuth2 configuration for a provider")
    @ApiResponse(responseCode = "204", description = "Configuration removed")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable OAuth2ProviderType provider, Authentication authentication) {
        var caller = currentClaims(authentication);
        oauth2ConfigService.delete(caller.organizationId(), provider);
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
