package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.LocalizationConfigService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.LocalizationConfigResponse;
import com.bablsoft.accessflow.security.internal.web.model.UpdateLocalizationConfigRequest;
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
@RequestMapping("/api/v1/admin/localization-config")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Localization Config",
        description = "Per-organization language settings: user-facing allow-list, default language, and AI-review language (ADMIN only)")
@RequiredArgsConstructor
class AdminLocalizationConfigController {

    private final LocalizationConfigService localizationConfigService;

    @GetMapping
    @Operation(summary = "Read the current localization configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Current localization configuration")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    LocalizationConfigResponse get(Authentication authentication) {
        var caller = currentClaims(authentication);
        return LocalizationConfigResponse.from(localizationConfigService.getOrDefault(caller.organizationId()));
    }

    @PutMapping
    @Operation(summary = "Update the localization configuration for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Updated localization configuration")
    @ApiResponse(responseCode = "400", description = "Validation error or unsupported language")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    LocalizationConfigResponse update(@Valid @RequestBody UpdateLocalizationConfigRequest body,
                                      Authentication authentication) {
        var caller = currentClaims(authentication);
        return LocalizationConfigResponse.from(
                localizationConfigService.update(caller.organizationId(), body.toCommand()));
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
