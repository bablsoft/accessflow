package com.bablsoft.accessflow.apigov.internal.scheduled;

import com.bablsoft.accessflow.apigov.internal.ApiExecutionService;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Fires deferred API requests whose {@code scheduled_for} has been reached. Clustered-safe via
 * ShedLock. Each request executes independently; a failure on one is logged and does not abort the
 * batch.
 */
@Component
@RequiredArgsConstructor
public class ApiRequestRunJob {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestRunJob.class);

    private final ApiRequestRepository requestRepository;
    private final ApiExecutionService executionService;

    @Scheduled(fixedDelayString = "${accessflow.apigov.scheduled-run-poll-interval:PT1M}")
    @SchedulerLock(name = "apiRequestRunJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        for (var id : requestRepository.findScheduledDueIds(Instant.now())) {
            try {
                executionService.execute(id);
            } catch (RuntimeException ex) {
                log.error("Failed to fire scheduled API request {}", id, ex);
            }
        }
    }
}
