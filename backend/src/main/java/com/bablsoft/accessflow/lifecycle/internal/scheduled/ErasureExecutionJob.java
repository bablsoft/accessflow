package com.bablsoft.accessflow.lifecycle.internal.scheduled;

import com.bablsoft.accessflow.lifecycle.internal.ErasureExecutionService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Clustered-safe execution of APPROVED right-to-erasure requests (AF-499): each tick picks up
 * APPROVED requests and runs the erasure, transitioning them to {@code EXECUTED}/{@code FAILED}.
 * Cadence is {@code accessflow.lifecycle.erasure-execution-interval} (default {@code PT1M}); the
 * {@code @SchedulerLock} ensures only one replica runs per tick. Per-request failures are swallowed
 * so one bad request cannot abort the batch.
 */
@Component
@RequiredArgsConstructor
class ErasureExecutionJob {

    private static final Logger log = LoggerFactory.getLogger(ErasureExecutionJob.class);

    private final ErasureExecutionService executionService;

    @Scheduled(fixedDelayString = "${accessflow.lifecycle.erasure-execution-interval:PT1M}")
    @SchedulerLock(name = "erasureExecutionJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT15S")
    public void run() {
        var ids = executionService.findApprovedIds();
        if (ids.isEmpty()) {
            log.debug("No approved erasure requests to execute");
            return;
        }
        int executed = 0;
        for (UUID id : ids) {
            try {
                if (executionService.execute(id)) {
                    executed++;
                }
            } catch (RuntimeException ex) {
                log.error("Failed to execute erasure request {}", id, ex);
            }
        }
        log.info("Executed {} approved erasure requests (scanned {})", executed, ids.size());
    }
}
