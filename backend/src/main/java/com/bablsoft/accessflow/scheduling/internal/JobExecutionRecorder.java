package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import com.bablsoft.accessflow.scheduling.internal.persistence.entity.JobExecutionEntity;
import com.bablsoft.accessflow.scheduling.internal.persistence.repo.JobExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Writes {@code job_executions} rows. Each write runs in its own transaction
 * ({@code REQUIRES_NEW}) so a job that fails and rolls back does not erase the record of its own
 * failure. Callers must treat every method as fallible and swallow what it throws — see
 * {@link ScheduledJobExecutionAdvisor}; recording must never change a job's outcome.
 */
@Service
@Slf4j
class JobExecutionRecorder {

    static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    private final JobExecutionRepository repository;
    private final Clock clock;
    private volatile String instanceId;

    JobExecutionRecorder(JobExecutionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID open(String jobName, String lockName) {
        var entity = new JobExecutionEntity();
        entity.setId(UUID.randomUUID());
        entity.setJobName(jobName);
        entity.setLockName(lockName);
        entity.setInstanceId(instanceId());
        entity.setStartedAt(clock.instant());
        entity.setStatus(JobExecutionStatus.RUNNING);
        return repository.save(entity).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void close(UUID executionId, Throwable failure) {
        var entity = repository.findById(executionId).orElse(null);
        if (entity == null) {
            log.warn("Job execution {} vanished before it could be closed", executionId);
            return;
        }
        var finishedAt = clock.instant();
        entity.setFinishedAt(finishedAt);
        entity.setDurationMs(Math.max(0, Duration.between(entity.getStartedAt(), finishedAt).toMillis()));
        if (failure == null) {
            entity.setStatus(JobExecutionStatus.SUCCESS);
        } else {
            entity.setStatus(JobExecutionStatus.FAILED);
            entity.setErrorClass(failure.getClass().getName());
            entity.setErrorMessage(truncate(failure.getMessage()));
        }
        repository.save(entity);
    }

    /** Resolved on first use, so a slow host-name lookup never delays bean creation. */
    String instanceId() {
        var resolved = instanceId;
        if (resolved == null) {
            resolved = resolveInstanceId(System.getenv("HOSTNAME"));
            instanceId = resolved;
        }
        return resolved;
    }

    static String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    static String resolveInstanceId(String hostnameEnv) {
        if (hostnameEnv != null && !hostnameEnv.isBlank()) {
            return hostnameEnv.strip();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "unknown";
        }
    }
}
