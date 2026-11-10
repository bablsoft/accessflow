package com.bablsoft.accessflow.deploygov.internal.scheduled;

import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.verification.DeploymentVerificationService;
import com.bablsoft.accessflow.deploygov.internal.verification.VerificationTickResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumMap;

/**
 * Samples every running post-deployment verification (#1025): each executed request whose
 * {@code SUCCEEDED} outcome report opened a window is sampled once per its snapshotted
 * {@code sampleInterval}; a breach flips the request {@code EXECUTED → FAILED}, a clean window ends
 * {@code PASSED}. No automatic rollback. The short {@code lockAtLeastFor} is deliberate — it caps
 * the effective cadence, and the e2e stack runs this job at seconds.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeploymentVerificationJob {

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentVerificationService verificationService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.deploygov.verification-check:PT30S}")
    @SchedulerLock(name = "deploymentVerificationJob", lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT5S")
    public void run() {
        var startedAt = clock.instant();
        var ids = requestRepository.findVerificationDueIds();
        if (ids.isEmpty()) {
            log.debug("No running deployment verifications");
            return;
        }
        var counts = new EnumMap<VerificationTickResult, Integer>(VerificationTickResult.class);
        for (var id : ids) {
            try {
                counts.merge(verificationService.verifyOne(id), 1, Integer::sum);
            } catch (RuntimeException ex) {
                // One bad row must not abort the batch; the next tick retries it.
                log.error("Failed to verify deployment request {}", id, ex);
            }
        }
        log.info("Deployment verification tick at {}: scanned {}, {}", startedAt, ids.size(),
                counts);
    }
}
