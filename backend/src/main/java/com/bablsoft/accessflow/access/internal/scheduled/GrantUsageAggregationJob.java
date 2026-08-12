package com.bablsoft.accessflow.access.internal.scheduled;

import com.bablsoft.accessflow.access.internal.GrantUsageAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Refreshes the per-grant usage summaries that back least-privilege intelligence (#625): reconciles
 * them against the live standing grants, folds new audit activity into them, recomputes each
 * revocation recommendation, and emits any due staleness nudges.
 *
 * <p>Poll interval is {@code accessflow.access.usage.aggregation-poll-interval} (default 1 hour).
 * The {@link SchedulerLock} guarantees only one node in a cluster executes per tick — without it
 * every replica would fold the same audit window and multiply every usage count. Per-organization
 * failures are logged and skipped so one bad tenant cannot abort the batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GrantUsageAggregationJob {

    private final GrantUsageAggregationService aggregationService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.access.usage.aggregation-poll-interval:PT1H}")
    @SchedulerLock(name = "grantUsageAggregationJob", lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT2M")
    public void run() {
        var now = clock.instant();
        var organizationIds = aggregationService.findOrganizationIds();
        if (organizationIds.isEmpty()) {
            log.debug("No organizations to aggregate grant usage for");
            return;
        }
        int aggregated = 0;
        int summaries = 0;
        for (var organizationId : organizationIds) {
            try {
                summaries += aggregationService.aggregateOrganization(organizationId, now);
                aggregated++;
            } catch (RuntimeException ex) {
                log.error("Grant usage aggregation failed for organization {}", organizationId, ex);
            }
        }
        log.info("Aggregated grant usage for {} organizations ({} summaries, scanned {})",
                aggregated, summaries, organizationIds.size());
    }
}
