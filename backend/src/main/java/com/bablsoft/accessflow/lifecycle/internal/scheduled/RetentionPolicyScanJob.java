package com.bablsoft.accessflow.lifecycle.internal.scheduled;

import com.bablsoft.accessflow.lifecycle.internal.RetentionPolicyScanService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clustered-safe retention scan (AF-499): each tick stages a {@code lifecycle_runs} row for every
 * enabled policy with eligible rows. Cadence is {@code accessflow.lifecycle.policy-scan-interval}
 * (default {@code PT1H}). The {@code @SchedulerLock} ensures only one replica runs per tick.
 */
@Component
@RequiredArgsConstructor
class RetentionPolicyScanJob {

    private final RetentionPolicyScanService scanService;

    @Scheduled(fixedDelayString = "${accessflow.lifecycle.policy-scan-interval:PT1H}")
    @SchedulerLock(name = "retentionPolicyScanJob", lockAtMostFor = "PT55M", lockAtLeastFor = "PT30S")
    public void run() {
        scanService.scanAndStage();
    }
}
