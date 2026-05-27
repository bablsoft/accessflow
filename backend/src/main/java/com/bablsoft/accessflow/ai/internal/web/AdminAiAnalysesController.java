package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.internal.AiAnalysisStatsService;
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

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ai-analyses")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin AI Analyses", description = "Aggregate stats over the AI analysis history (ADMIN only)")
@RequiredArgsConstructor
class AdminAiAnalysesController {

    private final AiAnalysisStatsService statsService;

    @GetMapping("/stats")
    @Operation(summary = "Aggregate AI analysis statistics for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Three time-bounded series: risk score by day, top issue categories, top submitters")
    @ApiResponse(responseCode = "400", description = "Invalid time window ('from' after 'to')")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    AiAnalysisStatsResponse stats(
            @Parameter(description = "Inclusive lower bound on ai_analyses.created_at; defaults to now - 30d")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Exclusive upper bound on ai_analyses.created_at; defaults to now")
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Optional datasource filter (org-scoped)")
            @RequestParam(required = false) UUID datasourceId,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        var raw = statsService.stats(organizationId, from, to, datasourceId);
        return AiAnalysisStatsResponse.from(raw);
    }
}
