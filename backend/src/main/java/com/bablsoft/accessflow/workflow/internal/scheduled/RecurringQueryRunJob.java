package com.bablsoft.accessflow.workflow.internal.scheduled;

import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Periodically picks up {@code APPROVED} recurring-series parents (#627) whose
 * {@code recurrence_next_run_at} cursor is at or before {@code now()} and triggers occurrence
 * execution via the workflow's lifecycle service, which re-checks permissions fail-closed, creates
 * the child occurrence row, advances the cursor, and executes through the full proxy pipeline
 * with {@code trigger=recurring} audit metadata. The parent stays {@code APPROVED}.
 *
 * <p>The poll interval is {@code accessflow.workflow.recurring-run-poll-interval} (default 1 min).
 * The {@link SchedulerLock} guarantees only one node in a cluster fires per tick — the
 * Redis-backed {@code LockProvider} lives in the {@code scheduling} module.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringQueryRunJob {

    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryLifecycleService queryLifecycleService;
    private final Clock clock;

    // lockAtLeastFor is deliberately short (unlike the sibling jobs' PT30S): the lock's minimum
    // hold caps the effective occurrence cadence when the poll interval is tuned down, and
    // double-firing is already impossible — createRecurringOccurrence advances the cursor under
    // a pessimistic lock before executing. ShedLock here only de-duplicates concurrent ticks.
    @Scheduled(fixedDelayString = "${accessflow.workflow.recurring-run-poll-interval:PT1M}")
    @SchedulerLock(name = "recurringQueryRunJob", lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT1S")
    public void run() {
        List<UUID> ids = queryRequestLookupService.findRecurringDueIds(clock.instant());
        if (ids.isEmpty()) {
            log.debug("No recurring queries are due");
            return;
        }
        int fired = 0;
        for (UUID id : ids) {
            try {
                queryLifecycleService.executeRecurringOccurrence(id);
                fired++;
            } catch (RuntimeException ex) {
                log.error("Failed to fire recurring occurrence for series {}", id, ex);
            }
        }
        log.info("Recurring-run job fired {} series (scanned {})", fired, ids.size());
    }
}
