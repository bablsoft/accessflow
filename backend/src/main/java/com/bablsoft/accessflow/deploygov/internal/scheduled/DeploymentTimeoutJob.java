package com.bablsoft.accessflow.deploygov.internal.scheduled;

import com.bablsoft.accessflow.deploygov.internal.DeploymentRequestStateService;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Auto-rejects {@code PENDING_REVIEW} deployment requests that have sat past their resolved review
 * plan's {@code approval_timeout_hours} to {@code TIMED_OUT} (#692) — the deployment twin of
 * {@code QueryTimeoutJob}, plan-driven like it (a request whose pipeline/environment resolves no
 * plan never times out). Separate job because {@code deployment_requests} is owned by deploygov.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeploymentTimeoutJob {

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentRequestStateService stateService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.deploygov.timeout-check:PT5M}")
    @SchedulerLock(name = "deploymentTimeoutJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        var ids = requestRepository.findStalePendingReviewIds(clock.instant());
        if (ids.isEmpty()) {
            log.debug("No deployment requests past approval timeout");
            return;
        }
        var rejected = 0;
        for (var id : ids) {
            try {
                if (stateService.markTimedOut(id)) {
                    rejected++;
                }
            } catch (RuntimeException ex) {
                // One bad row must not abort the batch; the next tick retries it.
                log.error("Failed to time out deployment request {}", id, ex);
            }
        }
        log.info("Auto-rejected {} deployment requests due to approval timeout (scanned {})",
                rejected, ids.size());
    }
}
