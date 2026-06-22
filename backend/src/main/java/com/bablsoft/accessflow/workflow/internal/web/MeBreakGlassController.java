package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.workflow.api.BreakGlassEligibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Reports which datasources the caller may currently break-glass on (AF-385). Backs the editor's
 * "Emergency access" button gating; available to any authenticated user.
 */
@RestController
@RequestMapping("/api/v1/me/break-glass")
@Tag(name = "Break-glass", description = "Emergency access eligibility")
@RequiredArgsConstructor
class MeBreakGlassController {

    private final BreakGlassEligibilityService eligibilityService;

    @GetMapping
    @Operation(summary = "List datasources the caller may break-glass on")
    @ApiResponse(responseCode = "200", description = "Eligible datasources returned")
    BreakGlassEligibilityResponse eligibility(
            @AuthenticationPrincipal(expression = "userId") UUID userId,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        return BreakGlassEligibilityResponse.from(
                eligibilityService.findEligible(userId, organizationId));
    }
}
