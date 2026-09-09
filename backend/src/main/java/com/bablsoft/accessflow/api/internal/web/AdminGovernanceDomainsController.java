package com.bablsoft.accessflow.api.internal.web;

import com.bablsoft.accessflow.api.internal.GovernanceDomainsService;
import com.bablsoft.accessflow.security.api.JwtClaims;
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

/**
 * The organization's own governance-domain switches (#926) — the in-app way back for an admin who
 * declined a domain in the first-run wizard, which before this needed a platform admin on
 * {@code /admin/organizations/:id}. Scoped to the caller's organization from the JWT: there is no
 * id in the path, so this can never edit another tenant. Gated on {@code SETUP_PROGRESS_VIEW},
 * the permission that already exposes both flags through {@code GET /admin/setup-progress}.
 */
@RestController
@RequestMapping("/api/v1/admin/governance-domains")
@PreAuthorize("hasAuthority('PERM_SETUP_PROGRESS_VIEW')")
@Tag(name = "Admin Governance Domains",
        description = "Which governance domains the caller's organization uses (ADMIN only)")
@RequiredArgsConstructor
class AdminGovernanceDomainsController {

    private final GovernanceDomainsService governanceDomainsService;

    @GetMapping
    @Operation(summary = "Return the governance domains the caller's organization has opted into")
    @ApiResponse(responseCode = "200", description = "Current domain flags")
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    GovernanceDomainsResponse get(Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        return GovernanceDomainsResponse.from(governanceDomainsService.get(caller.organizationId()));
    }

    @PutMapping
    @Operation(summary = "Replace the governance domains the caller's organization has opted into")
    @ApiResponse(responseCode = "200", description = "Updated domain flags")
    @ApiResponse(responseCode = "400", description = "Validation error (a flag is missing)")
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    GovernanceDomainsResponse update(Authentication authentication,
                                     @Valid @RequestBody UpdateGovernanceDomainsRequest request) {
        var caller = (JwtClaims) authentication.getPrincipal();
        return GovernanceDomainsResponse.from(governanceDomainsService.update(
                caller.organizationId(), request.governsApis(), request.governsDeployments()));
    }
}
