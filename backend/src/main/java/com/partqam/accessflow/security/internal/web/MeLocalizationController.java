package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.core.api.LocalizationConfigService;
import com.partqam.accessflow.core.api.UserPreferenceService;
import com.partqam.accessflow.security.api.JwtClaims;
import com.partqam.accessflow.security.internal.web.model.MeLocalizationResponse;
import com.partqam.accessflow.security.internal.web.model.UpdateMeLocalizationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/localization")
@Tag(name = "Me Localization", description = "Per-user UI language preference")
@RequiredArgsConstructor
class MeLocalizationController {

    private final LocalizationConfigService localizationConfigService;
    private final UserPreferenceService userPreferenceService;

    @GetMapping
    @Operation(summary = "Read the caller's localization options and current language")
    @ApiResponse(responseCode = "200", description = "Available languages, org default, and current preference")
    MeLocalizationResponse get(Authentication authentication) {
        var caller = currentClaims(authentication);
        var config = localizationConfigService.getOrDefault(caller.organizationId());
        var current = userPreferenceService.findPreferredLanguage(caller.userId())
                .orElse(config.defaultLanguage());
        return new MeLocalizationResponse(
                config.availableLanguages(),
                config.defaultLanguage(),
                current
        );
    }

    @PutMapping
    @Operation(summary = "Set the caller's preferred UI language")
    @ApiResponse(responseCode = "200", description = "Updated preference")
    @ApiResponse(responseCode = "400", description = "Unsupported language or not in the org allow-list")
    MeLocalizationResponse update(@Valid @RequestBody UpdateMeLocalizationRequest body,
                                  Authentication authentication) {
        var caller = currentClaims(authentication);
        userPreferenceService.setPreferredLanguage(caller.userId(), caller.organizationId(), body.language());
        return get(authentication);
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
