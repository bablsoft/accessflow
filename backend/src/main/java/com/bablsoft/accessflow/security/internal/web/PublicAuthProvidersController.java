package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.security.api.OAuth2ConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public endpoint the login page calls to learn which OAuth2 providers are currently enabled,
 * so it can render the right set of "Continue with..." buttons. No secrets are returned — only
 * the provider name and a human display label.
 */
@RestController
@RequestMapping("/api/v1/auth/oauth2/providers")
@Tag(name = "Authentication", description = "Public list of enabled OAuth2 providers")
@RequiredArgsConstructor
class PublicAuthProvidersController {

    private final OAuth2ConfigService oauth2ConfigService;
    private final OrganizationLookupService organizationLookupService;

    @GetMapping
    @Operation(summary = "List enabled OAuth2 providers (no auth required)")
    @ApiResponse(responseCode = "200", description = "Zero or more enabled providers")
    @SecurityRequirements
    List<OAuth2ProviderSummaryResponse> list() {
        var organizationId = organizationLookupService.singleOrganization();
        return oauth2ConfigService.listActive(organizationId).stream()
                .map(OAuth2ProviderSummaryResponse::from)
                .toList();
    }
}
