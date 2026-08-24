package com.bablsoft.accessflow.deploygov.internal.scheduled;

import com.bablsoft.accessflow.deploygov.internal.DefaultDeploymentGateService;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Announces releasability changes (#693): when an {@code APPROVED} request becomes releasable —
 * its {@code HOLD} freeze window closed or its {@code scheduled_for} instant passed — publish
 * {@code DeploymentReleasableEvent} exactly once ({@code release_notified_at} is the latch), so
 * push-style integrations learn the gate opened without tight polling. The gate itself stays the
 * source of truth; a request the freeze evaluator still holds is simply left unstamped and
 * rechecked next tick.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledDeploymentReleaseJob {

    private final DeploymentRequestRepository requestRepository;
    private final DefaultDeploymentGateService gateService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.deploygov.release-check:PT1M}")
    @SchedulerLock(name = "scheduledDeploymentReleaseJob", lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT30S")
    public void run() {
        var ids = requestRepository.findReleasableCandidateIds(clock.instant());
        if (ids.isEmpty()) {
            log.debug("No approved deployment requests awaiting a release announcement");
            return;
        }
        var announced = 0;
        for (var id : ids) {
            try {
                if (gateService.markReleasable(id)) {
                    announced++;
                }
            } catch (RuntimeException ex) {
                // One bad row must not abort the batch; the next tick retries it.
                log.error("Failed to announce releasability for deployment request {}", id, ex);
            }
        }
        log.info("Announced {} releasable deployment requests (scanned {})", announced, ids.size());
    }
}
