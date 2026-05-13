package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.security.api.OAuth2ConfigService;
import com.bablsoft.accessflow.security.api.SamlConfigService;
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
 * Public endpoints the login page calls to learn which sign-in methods are currently enabled,
 * so it can render the right set of "Continue with..." buttons. No secrets are returned — only
 * activation flags and human display labels.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Public sign-in method discovery")
@RequiredArgsConstructor
class PublicAuthProvidersController {

    private final OAuth2ConfigService oauth2ConfigService;
    private final SamlConfigService samlConfigService;
    private final OrganizationLookupService organizationLookupService;

    @GetMapping("/oauth2/providers")
    @Operation(summary = "List enabled OAuth2 providers (no auth required)")
    @ApiResponse(responseCode = "200", description = "Zero or more enabled providers")
    @SecurityRequirements
    List<OAuth2ProviderSummaryResponse> oauth2Providers() {
        var organizationId = organizationLookupService.singleOrganization();
        return oauth2ConfigService.listActive(organizationId).stream()
                .map(OAuth2ProviderSummaryResponse::from)
                .toList();
    }

    @GetMapping("/saml/enabled")
    @Operation(summary = "Report whether SAML SSO is enabled for this deployment (no auth required)")
    @ApiResponse(responseCode = "200", description = "Enabled flag for the single deployment org")
    @SecurityRequirements
    SamlEnabledResponse samlEnabled() {
        var organizationId = organizationLookupService.singleOrganization();
        return new SamlEnabledResponse(samlConfigService.getOrDefault(organizationId).active());
    }

    record SamlEnabledResponse(boolean enabled) {}
}
