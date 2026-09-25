package com.bablsoft.accessflow.core.internal.scheduled;

import com.bablsoft.accessflow.core.internal.config.DataBudgetProperties;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Deletes data-budget ledger rows (#942) older than {@code accessflow.core.data-budget
 * .usage-retention}. Rows past the longest budget window can no longer count toward any budget.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataBudgetUsagePruneJob {

    private final DataBudgetUsageRepository usageRepository;
    private final DataBudgetProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.core.data-budget.prune-interval:PT1H}")
    @SchedulerLock(name = "dataBudgetUsagePruneJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void run() {
        var cutoff = clock.instant().minus(properties.usageRetention());
        var deleted = usageRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Pruned {} data-budget usage rows older than {}", deleted, cutoff);
        } else {
            log.debug("No data-budget usage rows older than {}", cutoff);
        }
    }
}
