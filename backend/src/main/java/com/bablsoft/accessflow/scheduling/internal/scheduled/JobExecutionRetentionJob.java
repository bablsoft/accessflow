package com.bablsoft.accessflow.scheduling.internal.scheduled;

import com.bablsoft.accessflow.scheduling.internal.config.JobMonitoringProperties;
import com.bablsoft.accessflow.scheduling.internal.persistence.repo.JobExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Prunes {@code job_executions} on two axes (#923): by age ({@code retention}) and to each job's
 * newest {@code max-per-job} rows, so one {@code PT30S} job cannot crowd out a daily job's history.
 * Both are bulk deletes; nothing is loaded. A non-positive setting skips its axis. This job's own
 * runs are recorded like any other's.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JobExecutionRetentionJob {

    private final JobExecutionRepository repository;
    private final JobMonitoringProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.scheduling.executions.retention-poll-interval:PT6H}")
    @SchedulerLock(name = "jobExecutionRetentionJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void run() {
        RuntimeException failure = null;
        var retention = properties.retention();
        if (retention.isNegative() || retention.isZero()) {
            log.debug("Job execution age pruning skipped: retention {} is not positive", retention);
        } else {
            try {
                var deleted = repository.deleteStartedBefore(clock.instant().minus(retention));
                log.info("Pruned {} job executions older than {}", deleted, retention);
            } catch (RuntimeException ex) {
                log.error("Job execution age pruning failed", ex);
                failure = ex;
            }
        }
        var maxPerJob = properties.maxPerJob();
        if (maxPerJob <= 0) {
            log.debug("Job execution per-job cap skipped: max-per-job {} is not positive", maxPerJob);
        } else {
            try {
                var trimmed = repository.trimPerJob(maxPerJob);
                log.info("Trimmed {} job executions beyond {} per job", trimmed, maxPerJob);
            } catch (RuntimeException ex) {
                log.error("Job execution per-job trimming failed", ex);
                failure = failure == null ? ex : failure;
            }
        }
        // Both axes were attempted; rethrow so the run is recorded as FAILED rather than SUCCESS.
        if (failure != null) {
            throw failure;
        }
    }
}
