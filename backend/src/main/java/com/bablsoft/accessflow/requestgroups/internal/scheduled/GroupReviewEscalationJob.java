package com.bablsoft.accessflow.requestgroups.internal.scheduled;

import com.bablsoft.accessflow.requestgroups.internal.RequestGroupStateService;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
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
 * The grouped-request twin of {@code ReviewEscalationJob} (#622).
 *
 * <p>A bundle has no review plan of its own, so its escalation window is the <strong>minimum</strong>
 * non-null {@code escalation_after_hours} across its members' plans — the strictest member decides,
 * matching the weakest-link union {@code GroupReviewPlanResolver} already applies to approvers. A
 * member whose plan has escalation switched off contributes nothing to that minimum rather than
 * disabling escalation for the whole bundle.
 *
 * <p>Notify-only: neither path touches the decision or eligibility code.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GroupReviewEscalationJob {

    private final RequestGroupRepository groupRepository;
    private final RequestGroupStateService stateService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.requestgroups.escalation-poll-interval:PT5M}")
    @SchedulerLock(name = "groupReviewEscalationJob", lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT30S")
    public void run() {
        var now = clock.instant();
        var escalated = process("escalate", groupRepository.findEscalationDueIds(now),
                stateService::markEscalated, now);
        var nudged = process("nudge", groupRepository.findNudgeDueIds(now),
                stateService::markNudged, now);
        if (escalated > 0 || nudged > 0) {
            log.info("Group review escalation pass: escalated {}, nudged {}", escalated, nudged);
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
                log.error("Failed to {} request group {}", what, id, ex);
            }
        }
        return applied;
    }
}
