package com.bablsoft.accessflow.ai.internal.scheduled;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyDetectionService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Clustered-safe scheduled driver for behavioural anomaly detection (UBA, AF-383). Every
 * {@code accessflow.ai.anomaly.detection-poll-interval} it refreshes rolling baselines from the
 * audit stream and detects deviations. The per-subject loop (and its error swallowing) lives in
 * {@link BehaviorAnomalyDetectionService}; this job stays thin and guards the whole batch so a
 * single failure is logged rather than thrown out of the scheduler.
 */
@Component
@RequiredArgsConstructor
public class BehaviorAnomalyDetectionJob {

    private static final Logger log = LoggerFactory.getLogger(BehaviorAnomalyDetectionJob.class);

    private final BehaviorAnomalyDetectionService detectionService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.ai.anomaly.detection-poll-interval:PT15M}")
    @SchedulerLock(name = "behaviorAnomalyDetectionJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        try {
            int detected = detectionService.refreshAndDetectAll(clock.instant());
            if (detected > 0) {
                log.info("Behavior anomaly detection complete; {} anomalies detected", detected);
            } else {
                log.debug("Behavior anomaly detection complete; no anomalies");
            }
        } catch (RuntimeException ex) {
            log.error("Behavior anomaly detection run failed", ex);
        }
    }
}
