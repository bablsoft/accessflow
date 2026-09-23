package com.bablsoft.accessflow.scheduling.internal.web;

import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import com.bablsoft.accessflow.scheduling.api.JobMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Read-only monitoring of the {@code @Scheduled} jobs of this process (#923). Platform-scoped —
 * jobs run per process, not per organization — so it is gated on {@code PLATFORM_ADMIN} like the
 * other {@code /platform} endpoints. Monitoring only: nothing here triggers, pauses or reschedules.
 */
@RestController
@RequestMapping("/api/v1/platform/jobs")
@PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
@Tag(name = "Platform Jobs", description = "Scheduled job registry and execution history (platform admin only)")
@RequiredArgsConstructor
class JobMonitoringController {

    private final JobMonitoringService jobMonitoringService;

    @GetMapping
    @Operation(summary = "List the registered scheduled jobs with their health rollup")
    @ApiResponse(responseCode = "200", description = "Job registry")
    @ApiResponse(responseCode = "403", description = "Caller is not a platform admin")
    JobRegistryResponse registry() {
        return JobRegistryResponse.from(jobMonitoringService.registry());
    }

    @GetMapping("/{jobName}/executions")
    @Operation(summary = "Paginated execution history of one scheduled job, newest first")
    @ApiResponse(responseCode = "200", description = "Page of executions")
    @ApiResponse(responseCode = "403", description = "Caller is not a platform admin")
    @ApiResponse(responseCode = "404", description = "Job is neither registered nor has any history")
    JobExecutionPageResponse executions(@PathVariable String jobName,
                                        @RequestParam(required = false) JobExecutionStatus status,
                                        @RequestParam(required = false) Instant from,
                                        @RequestParam(required = false) Instant to,
                                        Pageable pageable) {
        return JobExecutionPageResponse.from(jobMonitoringService.executions(jobName, status, from, to,
                SpringPageableAdapter.toPageRequest(pageable)));
    }
}
