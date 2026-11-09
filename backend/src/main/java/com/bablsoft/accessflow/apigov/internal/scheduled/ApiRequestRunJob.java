package com.bablsoft.accessflow.apigov.internal.scheduled;

import com.bablsoft.accessflow.apigov.api.ApiConnectorQuotaExceededException;
import com.bablsoft.accessflow.apigov.internal.ApiExecutionService;
import com.bablsoft.accessflow.apigov.internal.ApiExecutionService.ExecutionTrigger;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Fires deferred API requests whose {@code scheduled_for} has been reached, and retries
 * rate-limited ones whose {@code throttled_until} has passed (#972). Clustered-safe via ShedLock.
 * Each request executes independently; a failure on one is logged and does not abort the batch.
 */
@Component
@RequiredArgsConstructor
public class ApiRequestRunJob {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestRunJob.class);

    private final ApiRequestRepository requestRepository;
    private final ApiExecutionService executionService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.apigov.scheduled-run-poll-interval:PT1M}")
    @SchedulerLock(name = "apiRequestRunJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        var now = clock.instant();
        drain(requestRepository.findScheduledDueIds(now), ExecutionTrigger.SCHEDULED);
        drain(requestRepository.findThrottledDueIds(now), ExecutionTrigger.QUOTA_RETRY);
    }

    private void drain(List<UUID> ids, ExecutionTrigger trigger) {
        for (var id : ids) {
            try {
                executionService.execute(id, trigger);
            } catch (ApiConnectorQuotaExceededException ex) {
                // Expected while a connector stays saturated — the request is re-stamped and retried
                // on a later tick. Logging this at ERROR would flood the log for a long throttle.
                log.debug("API request {} still throttled ({}), retry at {}", id, ex.limitKind(),
                        ex.throttledUntil());
            } catch (RuntimeException ex) {
                log.error("Failed to fire {} API request {}", trigger, id, ex);
            }
        }
    }
}
