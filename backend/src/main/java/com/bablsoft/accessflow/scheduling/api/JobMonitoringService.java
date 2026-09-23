package com.bablsoft.accessflow.scheduling.api;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.time.Instant;

/**
 * Read-only view of the {@code @Scheduled} jobs of this process and their recorded executions.
 * Platform-scoped: jobs run per process, not per organization.
 */
public interface JobMonitoringService {

    /** Every registered job merged with its health rollup, plus jobs that only have history. */
    JobRegistryView registry();

    /**
     * Paginated execution history of one job, newest first. Every filter is optional.
     *
     * @throws JobNotFoundException when the job is neither registered nor has any recorded run
     */
    PageResponse<JobExecutionView> executions(String jobName, JobExecutionStatus status,
                                              Instant from, Instant to, PageRequest pageRequest);
}
