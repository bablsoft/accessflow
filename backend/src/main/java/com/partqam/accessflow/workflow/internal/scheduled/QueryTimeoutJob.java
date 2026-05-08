package com.partqam.accessflow.workflow.internal.scheduled;

import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Periodically auto-rejects queries that have been {@code PENDING_REVIEW} longer than their plan's
 * {@code approval_timeout_hours}.
 *
 * <p>The poll interval is {@code accessflow.workflow.timeout-poll-interval} (default 5 minutes).
 * The {@link SchedulerLock} guarantees only one node in a cluster executes per tick — see
 * {@code RedisLockProviderConfiguration}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QueryTimeoutJob {

    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryRequestStateService queryRequestStateService;

    @Scheduled(fixedDelayString = "${accessflow.workflow.timeout-poll-interval:PT5M}")
    @SchedulerLock(name = "queryTimeoutJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        List<UUID> ids = queryRequestLookupService.findTimedOutPendingReviewIds(Instant.now());
        if (ids.isEmpty()) {
            log.debug("No queries past approval timeout");
            return;
        }
        int rejected = 0;
        for (UUID id : ids) {
            try {
                if (queryRequestStateService.markTimedOut(id)) {
                    rejected++;
                }
            } catch (RuntimeException ex) {
                log.error("Failed to auto-reject timed-out query {}", id, ex);
            }
        }
        log.info("Auto-rejected {} queries due to approval timeout (scanned {})",
                rejected, ids.size());
    }
}
