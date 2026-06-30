package com.bablsoft.accessflow.requestgroups.internal.scheduled;

import com.bablsoft.accessflow.requestgroups.internal.RequestGroupStateService;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Clustered-safe scan that auto-rejects groups stuck in PENDING_REVIEW past the approval timeout. */
@Component
@RequiredArgsConstructor
public class GroupTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(GroupTimeoutJob.class);

    private final RequestGroupRepository requestGroupRepository;
    private final RequestGroupStateService stateService;

    @Value("${accessflow.requestgroups.approval-timeout:PT24H}")
    private Duration approvalTimeout;

    @Scheduled(fixedDelayString = "${accessflow.requestgroups.timeout-poll-interval:PT5M}")
    @SchedulerLock(name = "groupTimeoutJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        var cutoff = Instant.now().minus(approvalTimeout);
        var ids = requestGroupRepository.findStalePendingReviewIds(cutoff);
        if (ids.isEmpty()) {
            log.debug("No request groups past approval timeout");
            return;
        }
        int rejected = 0;
        for (UUID id : ids) {
            try {
                if (stateService.markTimedOut(id)) {
                    rejected++;
                }
            } catch (RuntimeException ex) {
                log.error("Failed to auto-reject timed-out request group {}", id, ex);
            }
        }
        log.info("Auto-rejected {} request groups due to approval timeout (scanned {})",
                rejected, ids.size());
    }
}
