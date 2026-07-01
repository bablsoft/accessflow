package com.bablsoft.accessflow.lifecycle.internal.scheduled;

import com.bablsoft.accessflow.lifecycle.internal.RetentionPolicyExecutionService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Clustered-safe drainer of STAGED retention-policy runs (AF-519): each tick executes the runs the
 * scan job staged (the scan honours per-policy cron schedules when deciding what to stage), applying
 * the policy action through the proxy and transitioning each run to {@code COMPLETED}/{@code FAILED}.
 * Cadence is {@code accessflow.lifecycle.policy-execution-interval} (default {@code PT5M}); the
 * {@code @SchedulerLock} ensures only one replica runs per tick. Per-run failures are swallowed so
 * one bad run cannot abort the batch.
 */
@Component
@RequiredArgsConstructor
class RetentionPolicyExecutionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyExecutionJob.class);

    private final RetentionPolicyExecutionService executionService;

    @Scheduled(fixedDelayString = "${accessflow.lifecycle.policy-execution-interval:PT5M}")
    @SchedulerLock(name = "retentionPolicyExecutionJob", lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT30S")
    public void run() {
        var ids = executionService.findStagedRunIds();
        if (ids.isEmpty()) {
            log.debug("No staged retention runs to execute");
            return;
        }
        int executed = 0;
        for (UUID id : ids) {
            try {
                if (executionService.execute(id)) {
                    executed++;
                }
            } catch (RuntimeException ex) {
                log.error("Failed to execute retention run {}", id, ex);
            }
        }
        log.info("Executed {} staged retention runs (scanned {})", executed, ids.size());
    }
}
