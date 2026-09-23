package com.bablsoft.accessflow.scheduling.internal.web;

import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import com.bablsoft.accessflow.scheduling.api.JobExecutionView;

import java.time.Instant;
import java.util.UUID;

public record JobExecutionResponse(
        UUID id,
        String jobName,
        String lockName,
        String instanceId,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        JobExecutionStatus status,
        String errorClass,
        String errorMessage,
        boolean abandoned) {

    static JobExecutionResponse from(JobExecutionView view) {
        return new JobExecutionResponse(view.id(), view.jobName(), view.lockName(), view.instanceId(),
                view.startedAt(), view.finishedAt(), view.durationMs(), view.status(), view.errorClass(),
                view.errorMessage(), view.abandoned());
    }
}
