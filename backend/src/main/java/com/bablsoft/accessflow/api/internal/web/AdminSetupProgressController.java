package com.bablsoft.accessflow.api.internal.web;

import com.bablsoft.accessflow.api.internal.SetupProgressService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/setup-progress")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Setup Progress", description = "Onboarding progress for the caller's organization (ADMIN only)")
@RequiredArgsConstructor
class AdminSetupProgressController {

    private final SetupProgressService setupProgressService;

    @GetMapping
    @Operation(summary = "Return which setup steps the caller's organization has completed")
    @ApiResponse(responseCode = "200", description = "Setup progress snapshot")
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    AdminSetupProgressResponse get(Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        return AdminSetupProgressResponse.from(setupProgressService.getProgress(caller.organizationId()));
    }
}
