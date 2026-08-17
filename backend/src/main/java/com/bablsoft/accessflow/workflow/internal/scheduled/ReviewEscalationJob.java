package com.bablsoft.accessflow.workflow.internal.scheduled;

import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Warns before the hard timeout (#622).
 *
 * <p>Without this, an approval chain stalls in silence: a request sits in {@code PENDING_REVIEW}
 * until {@code QueryTimeoutJob} auto-rejects it at {@code approval_timeout_hours}, and the
 * submitter finds out by being rejected. This job fires the two warning shots the review plan
 * configures — escalate once past {@code escalation_after_hours}, and re-nudge the reviewers who
 * still have not acted on the {@code nudge_interval_hours} cadence.
 *
 * <p><strong>Notify-only.</strong> Neither path widens who may approve. Idleness must never become
 * a way around the configured approver set — that is a governance regression an auditor would
 * rightly object to, and it is why escalation lives here rather than in the eligibility path.
 *
 * <p>Both marks are stamped under a row lock inside the state service, which is what makes the job
 * safe to run on every replica: the stamp, not the scan, decides who wins.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewEscalationJob {

    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryRequestStateService queryRequestStateService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.workflow.escalation-poll-interval:PT5M}")
    @SchedulerLock(name = "reviewEscalationJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        var now = clock.instant();
        var escalated = process("escalate", queryRequestLookupService.findEscalationDueIds(now),
                (id, at) -> queryRequestStateService.markEscalated(id, at), now);
        var nudged = process("nudge", queryRequestLookupService.findNudgeDueIds(now),
                (id, at) -> queryRequestStateService.markNudged(id, at), now);
        if (escalated > 0 || nudged > 0) {
            log.info("Review escalation pass: escalated {}, nudged {}", escalated, nudged);
        }
    }

    /**
     * Applies one mark across a scanned batch. A row that no longer qualifies — decided between the
     * scan and the lock, or already handled by a sibling replica — simply returns false and is not
     * counted; only a genuine failure is logged.
     */
    private int process(String what, List<UUID> ids, BiPredicate<UUID, java.time.Instant> mark,
                        java.time.Instant now) {
        var applied = 0;
        for (var id : ids) {
            try {
                if (mark.test(id, now)) {
                    applied++;
                }
            } catch (RuntimeException ex) {
                // One bad row must not abort the batch — the rest of the queue still needs its
                // warning, and the next tick will retry this one.
                log.error("Failed to {} query {}", what, id, ex);
            }
        }
        return applied;
    }
}
