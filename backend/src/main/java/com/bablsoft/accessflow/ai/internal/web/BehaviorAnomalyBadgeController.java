package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The current user's own anomaly badge (UBA, AF-383) — open-anomaly count + top score, optionally
 * scoped to one datasource. Available to any authenticated user; always scoped to the caller's own
 * id, so it never leaks another user's anomalies.
 */
@RestController
@RequestMapping("/api/v1/anomalies")
@Tag(name = "Behavior Anomaly Badge", description = "The caller's own anomaly badge")
@RequiredArgsConstructor
class BehaviorAnomalyBadgeController {

    private final BehaviorAnomalyLookupService anomalyLookupService;

    @GetMapping("/badge")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Open-anomaly badge for the current user (optionally one datasource)")
    @ApiResponse(responseCode = "200", description = "The badge (openCount + maxScore)")
    AnomalyBadgeResponse badge(
            @Parameter(description = "Optional datasource scope") @RequestParam(required = false) UUID datasourceId,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID userId) {
        var view = datasourceId == null
                ? anomalyLookupService.badgeForUser(organizationId, userId)
                : anomalyLookupService.badgeForUserDatasource(organizationId, userId, datasourceId);
        return AnomalyBadgeResponse.from(view);
    }
}
