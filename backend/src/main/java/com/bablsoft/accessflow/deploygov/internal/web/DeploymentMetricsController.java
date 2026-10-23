package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-time DORA and approval-SLA metrics (#984). Like the version inventory, carries
 * <strong>no class-level {@code @PreAuthorize}</strong>: the per-pipeline endpoint is open to
 * effective {@code can_trigger} holders and the service answers 404, never 403. Only the
 * org-wide endpoint carries a method-level functional gate.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Deployment Metrics",
        description = "DORA metrics and approval SLA for deployments (#984, epic #983)")
@RequiredArgsConstructor
class DeploymentMetricsController {

    private final DeploymentMetricsService metricsService;

    @GetMapping("/deployment-pipelines/{id}/metrics")
    @Operation(summary = "DORA and approval-SLA metrics for one pipeline over [from, to)")
    @ApiResponse(responseCode = "200", description = "Metrics for the window")
    @ApiResponse(responseCode = "400",
            description = "from >= to, or the window spans more than 366 days")
    @ApiResponse(responseCode = "404",
            description = "Pipeline not found, or not visible to the caller")
    DeploymentMetricsResponse pipelineMetrics(
            @PathVariable UUID id,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "environment", required = false) String environment,
            Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentMetricsResponse.from(metricsService.pipelineMetrics(id,
                caller.organizationId(), caller.userId(), caller.permissions(),
                new DeploymentMetricsFilter(from, to, environment, null)));
    }

    @GetMapping("/deployment-metrics")
    @PreAuthorize("hasAnyAuthority('PERM_DEPLOYMENT_PIPELINE_MANAGE',"
            + "'PERM_DEPLOYMENT_REVIEW','PERM_QUERY_ADMIN')")
    @Operation(summary = "DORA and approval-SLA metrics across the organization over [from, to)")
    @ApiResponse(responseCode = "200", description = "Metrics for the window")
    @ApiResponse(responseCode = "400",
            description = "from >= to, or the window spans more than 366 days")
    @ApiResponse(responseCode = "403",
            description = "Caller holds none of the required permissions")
    DeploymentMetricsResponse organizationMetrics(
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "environment", required = false) String environment,
            @RequestParam(name = "pipeline_id", required = false) UUID pipelineId,
            Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentMetricsResponse.from(metricsService.organizationMetrics(
                caller.organizationId(),
                new DeploymentMetricsFilter(from, to, environment, pipelineId)));
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
