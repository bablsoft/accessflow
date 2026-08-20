package com.bablsoft.accessflow.audit.internal.scheduled;

import com.bablsoft.accessflow.audit.internal.sink.AuditSinkDrainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Streams new {@code audit_log} rows to every enabled external audit sink (#628). Strictly
 * downstream of the audit write path: a dead sink only stalls its own cursor, never an audit
 * write. The {@link SchedulerLock} guarantees one node per tick — without it every replica
 * would deliver every batch once, breaking the sinks' dedupe expectations at scale. Per-sink
 * failures are isolated inside {@link AuditSinkDrainService}.
 *
 * <p>Tick budget: the HTTP and syslog deliverers enforce ≤10s connect/read timeouts; the S3
 * deliverer is the worst case at two puts × 30s api-call timeout per batch (~5 min for a slow
 * S3 sink at the default 5-batch cap). A tick that still overruns {@code lockAtMostFor} is
 * safe — the cursor is optimistic-locked and delivery is at-least-once — it just lets a second
 * replica start early.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditSinkDrainJob {

    private final AuditSinkDrainService drainService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.audit.sinks.drain-interval:PT30S}")
    @SchedulerLock(name = "auditSinkDrainJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT20S")
    public void run() {
        int delivered = drainService.drainAll(clock.instant());
        if (delivered > 0) {
            log.info("Drained audit rows to {} sink(s)", delivered);
        }
    }
}
