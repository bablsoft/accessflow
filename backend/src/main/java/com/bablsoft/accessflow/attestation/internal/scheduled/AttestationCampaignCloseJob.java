package com.bablsoft.accessflow.attestation.internal.scheduled;

import com.bablsoft.accessflow.attestation.api.AttestationLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Closes attestation campaigns whose {@code due_at} has passed — applying each campaign's
 * pending-default (KEEP / REVOKE) to items still left PENDING.
 *
 * <p>Poll interval is {@code accessflow.attestation.close-poll-interval} (default 5 minutes). The
 * {@link SchedulerLock} guarantees only one node in a cluster executes per tick. Per-campaign
 * failures are logged and skipped so one bad campaign cannot abort the batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AttestationCampaignCloseJob {

    private final AttestationLifecycleService lifecycleService;

    @Scheduled(fixedDelayString = "${accessflow.attestation.close-poll-interval:PT5M}")
    @SchedulerLock(name = "attestationCampaignCloseJob", lockAtMostFor = "PT15M",
            lockAtLeastFor = "PT30S")
    public void run() {
        List<UUID> ids = lifecycleService.findCampaignIdsDueToClose(Instant.now());
        if (ids.isEmpty()) {
            log.debug("No attestation campaigns due to close");
            return;
        }
        int closed = 0;
        for (UUID id : ids) {
            try {
                if (lifecycleService.closeCampaign(id)) {
                    closed++;
                }
            } catch (RuntimeException ex) {
                log.error("Failed to close attestation campaign {}", id, ex);
            }
        }
        log.info("Closed {} attestation campaigns (scanned {})", closed, ids.size());
    }
}
