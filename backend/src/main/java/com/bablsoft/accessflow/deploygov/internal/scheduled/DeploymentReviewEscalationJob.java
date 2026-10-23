package com.bablsoft.accessflow.deploygov.internal.scheduled;

import com.bablsoft.accessflow.deploygov.internal.DeploymentRequestStateService;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * The deployment twin of {@code ApiReviewEscalationJob} (#985): escalates deployment reviews that
 * have sat in {@code PENDING_REVIEW} past their resolved review plan's
 * {@code escalation_after_hours} (environment override, else the pipeline's), and re-nudges
 * undecided reviewers on {@code nudge_interval_hours}. Both clocks run from {@code created_at}, like
 * {@code DeploymentTimeoutJob}, so an escalation always precedes the timeout it warns about.
 *
 * <p>Notify-only: neither path touches eligibility, the self-approval rule or the decision code.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeploymentReviewEscalationJob {

    private final DeploymentRequestRepository requestRepository;
    private final DeploymentRequestStateService stateService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.deploygov.escalation-poll-interval:PT5M}")
    @SchedulerLock(name = "deploymentReviewEscalationJob", lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT30S")
    public void run() {
        var now = clock.instant();
        var escalated = process("escalate", requestRepository.findEscalationDueIds(now),
                stateService::markEscalated, now);
        var nudged = process("nudge", requestRepository.findNudgeDueIds(now),
                stateService::markNudged, now);
        if (escalated > 0 || nudged > 0) {
            log.info("Deployment review escalation pass: escalated {}, nudged {}", escalated,
                    nudged);
        }
    }

    private int process(String what, List<UUID> ids, BiPredicate<UUID, Instant> mark, Instant now) {
        var applied = 0;
        for (var id : ids) {
            try {
                if (mark.test(id, now)) {
                    applied++;
                }
            } catch (RuntimeException ex) {
                // One bad row must not abort the batch; the next tick retries it.
                log.error("Failed to {} deployment request {}", what, id, ex);
            }
        }
        return applied;
    }
}
