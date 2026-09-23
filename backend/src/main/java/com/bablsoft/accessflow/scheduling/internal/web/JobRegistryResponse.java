package com.bablsoft.accessflow.scheduling.internal.web;

import com.bablsoft.accessflow.scheduling.api.JobCadenceType;
import com.bablsoft.accessflow.scheduling.api.JobDescriptor;
import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import com.bablsoft.accessflow.scheduling.api.JobHealthSummary;
import com.bablsoft.accessflow.scheduling.api.JobRegistryView;

import java.time.Instant;
import java.util.List;

/**
 * The job registry. Durations are ISO-8601 strings; {@code scheduling_enabled=false} means the
 * scheduler is switched off on this process, so an empty {@code jobs} list is expected.
 */
public record JobRegistryResponse(
        boolean schedulingEnabled,
        boolean recordingEnabled,
        String summaryWindow,
        List<Job> jobs) {

    static JobRegistryResponse from(JobRegistryView view) {
        return new JobRegistryResponse(view.schedulingEnabled(), view.recordingEnabled(),
                view.summaryWindow().toString(), view.jobs().stream().map(Job::from).toList());
    }

    public record Job(
            String jobName,
            String declaringClass,
            String methodName,
            String module,
            JobCadenceType cadenceType,
            String cadence,
            String lockName,
            String lockAtMostFor,
            boolean registered,
            Health health) {

        static Job from(JobDescriptor job) {
            return new Job(job.jobName(), job.declaringClass(), job.methodName(), job.module(),
                    job.cadenceType(), job.cadence(), job.lockName(),
                    job.lockAtMostFor() != null ? job.lockAtMostFor().toString() : null,
                    job.registered(), Health.from(job.health()));
        }
    }

    public record Health(
            JobExecutionStatus lastStatus,
            boolean lastAbandoned,
            Instant lastStartedAt,
            Instant lastFinishedAt,
            Long lastDurationMs,
            String lastErrorMessage,
            long consecutiveFailures,
            long windowSuccessCount,
            long windowFailureCount,
            Long windowMeanDurationMs) {

        static Health from(JobHealthSummary health) {
            return new Health(health.lastStatus(), health.lastAbandoned(), health.lastStartedAt(),
                    health.lastFinishedAt(), health.lastDurationMs(), health.lastErrorMessage(),
                    health.consecutiveFailures(), health.windowSuccessCount(), health.windowFailureCount(),
                    health.windowMeanDurationMs());
        }
    }
}
