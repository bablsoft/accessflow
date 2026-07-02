package com.bablsoft.accessflow.lifecycle.internal.scheduled;

import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.internal.ErasureRequestStateService;
import com.bablsoft.accessflow.lifecycle.internal.config.LifecycleProperties;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Clustered-safe auto-reject of erasure requests stuck in {@code PENDING_REVIEW} past the review
 * timeout (AF-519), mirroring {@code QueryTimeoutJob}. Cadence is
 * {@code accessflow.lifecycle.review-timeout-poll-interval} (default {@code PT5M}); the timeout basis
 * is {@code accessflow.lifecycle.review-timeout} (default {@code PT168H}). The {@code @SchedulerLock}
 * ensures only one replica runs per tick, and per-row failures are swallowed so one bad row cannot
 * abort the batch.
 */
@Component
@RequiredArgsConstructor
class ErasureReviewTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(ErasureReviewTimeoutJob.class);

    private final DeletionRequestRepository requestRepository;
    private final ErasureRequestStateService stateService;
    private final LifecycleProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.lifecycle.review-timeout-poll-interval:PT5M}")
    @SchedulerLock(name = "erasureReviewTimeoutJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        var cutoff = clock.instant().minus(properties.reviewTimeout());
        var ids = requestRepository.findTimedOutPendingReviewIds(ErasureStatus.PENDING_REVIEW, cutoff);
        if (ids.isEmpty()) {
            log.debug("No erasure requests past review timeout");
            return;
        }
        int rejected = 0;
        for (UUID id : ids) {
            try {
                if (stateService.markTimedOut(id)) {
                    rejected++;
                }
            } catch (RuntimeException ex) {
                log.error("Failed to auto-reject timed-out erasure request {}", id, ex);
            }
        }
        log.info("Auto-rejected {} erasure requests due to review timeout (scanned {})",
                rejected, ids.size());
    }
}
