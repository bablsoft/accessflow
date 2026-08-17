package com.bablsoft.accessflow.apigov.internal.scheduled;

import com.bablsoft.accessflow.apigov.internal.ApiRequestStateService;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
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
 * The API-request twin of {@code ReviewEscalationJob} (#622): escalates governed API calls that
 * have sat unreviewed past their connector review plan's {@code escalation_after_hours}, and
 * re-nudges undecided reviewers on {@code nudge_interval_hours}.
 *
 * <p>Separate from the query job because {@code api_requests} is owned by {@code apigov} — the same
 * reason {@code ApiRequestTimeoutJob} sits beside {@code QueryTimeoutJob} rather than inside it.
 *
 * <p>Notify-only: neither path touches the decision or eligibility code.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiReviewEscalationJob {

    private final ApiRequestRepository requestRepository;
    private final ApiRequestStateService stateService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.apigov.escalation-poll-interval:PT5M}")
    @SchedulerLock(name = "apiReviewEscalationJob", lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT30S")
    public void run() {
        var now = clock.instant();
        var escalated = process("escalate", requestRepository.findEscalationDueIds(now),
                stateService::markEscalated, now);
        var nudged = process("nudge", requestRepository.findNudgeDueIds(now),
                stateService::markNudged, now);
        if (escalated > 0 || nudged > 0) {
            log.info("API review escalation pass: escalated {}, nudged {}", escalated, nudged);
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
                log.error("Failed to {} API request {}", what, id, ex);
            }
        }
        return applied;
    }
}
