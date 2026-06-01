package com.bablsoft.accessflow.access.internal.scheduled;

import com.bablsoft.accessflow.access.api.AccessGrantExpiryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Periodically revokes JIT access grants whose {@code expires_at} has passed.
 *
 * <p>The poll interval is {@code accessflow.access.grant-expiry-poll-interval} (default 5 minutes).
 * The {@link SchedulerLock} guarantees only one node in a cluster executes per tick — the
 * Redis-backed {@code LockProvider} lives in the {@code scheduling} module. Per-row failures are
 * logged and skipped so one bad grant cannot abort the batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccessGrantExpiryJob {

    private final AccessGrantExpiryService accessGrantExpiryService;

    @Scheduled(fixedDelayString = "${accessflow.access.grant-expiry-poll-interval:PT5M}")
    @SchedulerLock(name = "accessGrantExpiryJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        List<UUID> ids = accessGrantExpiryService.findExpiredGrantedIds(Instant.now());
        if (ids.isEmpty()) {
            log.debug("No access grants past expiry");
            return;
        }
        int revoked = 0;
        for (UUID id : ids) {
            try {
                if (accessGrantExpiryService.expireAndRevoke(id)) {
                    revoked++;
                }
            } catch (RuntimeException ex) {
                log.error("Failed to expire access grant {}", id, ex);
            }
        }
        log.info("Revoked {} expired access grants (scanned {})", revoked, ids.size());
    }
}
