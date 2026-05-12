package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.api.SamlConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/saml-config")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin SAML Config", description = "Per-organization SAML/SSO settings (ADMIN only)")
@RequiredArgsConstructor
class AdminSamlConfigController {

    private final SamlConfigService samlConfigService;

    @GetMapping
    @Operation(summary = "Read the SAML configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Current configuration (signing certificate masked)")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    SamlConfigResponse get(Authentication authentication) {
        var caller = currentClaims(authentication);
        return SamlConfigResponse.from(samlConfigService.getOrDefault(caller.organizationId()));
    }

    @PutMapping
    @Operation(summary = "Update the SAML configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Updated configuration (signing certificate masked)")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    SamlConfigResponse update(@Valid @RequestBody UpdateSamlConfigRequest body, Authentication authentication) {
        var caller = currentClaims(authentication);
        return SamlConfigResponse.from(samlConfigService.update(caller.organizationId(), body.toCommand()));
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
