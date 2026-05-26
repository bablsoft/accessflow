package com.bablsoft.accessflow.workflow.internal.scheduled;

import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Periodically picks up {@code APPROVED} queries whose {@code scheduled_for} timestamp is at or
 * before {@code now()} and triggers execution via the workflow's lifecycle service. The lifecycle
 * service records the submitter as the audit actor and tags the audit metadata with
 * {@code trigger=scheduled}.
 *
 * <p>The poll interval is {@code accessflow.workflow.scheduled-run-poll-interval} (default 1 min).
 * The {@link SchedulerLock} guarantees only one node in a cluster fires per tick — the
 * Redis-backed {@code LockProvider} lives in the {@code scheduling} module.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledQueryRunJob {

    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryLifecycleService queryLifecycleService;

    @Scheduled(fixedDelayString = "${accessflow.workflow.scheduled-run-poll-interval:PT1M}")
    @SchedulerLock(name = "scheduledQueryRunJob", lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT30S")
    public void run() {
        List<UUID> ids = queryRequestLookupService.findScheduledDueIds(Instant.now());
        if (ids.isEmpty()) {
            log.debug("No scheduled queries are due");
            return;
        }
        int fired = 0;
        for (UUID id : ids) {
            try {
                queryLifecycleService.executeScheduled(id);
                fired++;
            } catch (RuntimeException ex) {
                log.error("Failed to fire scheduled query {}", id, ex);
            }
        }
        log.info("Scheduled-run job fired {} queries (scanned {})", fired, ids.size());
    }
}
