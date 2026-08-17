package com.bablsoft.accessflow.requestgroups.internal.scheduled;

import com.bablsoft.accessflow.requestgroups.internal.RequestGroupStateService;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * The grouped-request twin of {@code ReviewEscalationJob} (#622).
 *
 * <p>A bundle has no review plan of its own, so its escalation window is the <strong>minimum</strong>
 * non-null {@code escalation_after_hours} across its members' plans — the strictest member decides,
 * matching the weakest-link union {@code GroupReviewPlanResolver} already applies to approvers. A
 * member whose plan has escalation switched off contributes nothing to that minimum rather than
 * disabling escalation for the whole bundle.
 *
 * <p>There is deliberately <strong>no nudge half</strong>, unlike the query and API-request twins.
 * A nudge is a reminder sent to someone, and {@code requestgroups} has no notification path at all
 * — so a group nudge could only advance a cursor nobody reads, re-writing every pending bundle on
 * every interval for no observable effect. The escalation stamp earns its keep by being once-only
 * and recording which bundles went idle; a repeating no-op write does not.
 *
 * <p>Notify-only: this touches no decision or eligibility code.
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
        var escalated = 0;
        for (var id : groupRepository.findEscalationDueIds(now)) {
            try {
                if (stateService.markEscalated(id, now)) {
                    escalated++;
                }
            } catch (RuntimeException ex) {
                // One bad row must not abort the batch; the next tick retries it.
                log.error("Failed to escalate request group {}", id, ex);
            }
        }
        if (escalated > 0) {
            log.info("Group review escalation pass: escalated {}", escalated);
        }
    }
}
