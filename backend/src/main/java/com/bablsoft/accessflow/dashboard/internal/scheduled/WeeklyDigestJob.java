package com.bablsoft.accessflow.dashboard.internal.scheduled;

import com.bablsoft.accessflow.dashboard.internal.WeeklyDigestDispatchService;
import com.bablsoft.accessflow.dashboard.internal.config.DashboardProperties;
import com.bablsoft.accessflow.dashboard.internal.persistence.entity.DashboardDigestSubscriptionEntity;
import com.bablsoft.accessflow.dashboard.internal.persistence.repo.DashboardDigestSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Builds and publishes the opt-in weekly dashboard digest (AF-498). Wakes every
 * {@code accessflow.dashboard.weekly-digest.poll-interval} (default 1 day) and, for every enabled
 * subscription not sent within {@code accessflow.dashboard.weekly-digest.period} (default 7 days),
 * builds that user's summary, publishes a {@code WeeklyDigestReadyEvent} (consumed by the
 * {@code notifications} module), and stamps {@code last_sent_at}.
 *
 * <p>The {@link SchedulerLock} guarantees only one node in a cluster runs per tick (Redis-backed
 * {@code LockProvider} in the {@code scheduling} module). Per-row failures are logged and skipped so
 * one bad subscription never aborts the batch. The per-row work runs in {@link
 * WeeklyDigestDispatchService} so the event is published inside a committed transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WeeklyDigestJob {

    private final DashboardDigestSubscriptionRepository subscriptionRepository;
    private final WeeklyDigestDispatchService dispatchService;
    private final DashboardProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.dashboard.weekly-digest.poll-interval:P1D}")
    @SchedulerLock(name = "weeklyDigestJob", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void run() {
        Instant now = clock.instant();
        Instant before = now.minus(properties.weeklyDigest().period());
        List<DashboardDigestSubscriptionEntity> due = subscriptionRepository.findDue(before);
        if (due.isEmpty()) {
            log.debug("No weekly digests due");
            return;
        }
        int sent = 0;
        for (var subscription : due) {
            try {
                dispatchService.publishDigest(subscription.getId(), now);
                sent++;
            } catch (RuntimeException ex) {
                log.error("Failed to build/publish weekly digest for user {}",
                        subscription.getUserId(), ex);
            }
        }
        log.info("Published {} weekly digests (scanned {})", sent, due.size());
    }
}
