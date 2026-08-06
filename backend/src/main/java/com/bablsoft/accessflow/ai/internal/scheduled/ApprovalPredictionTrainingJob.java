package com.bablsoft.accessflow.ai.internal.scheduled;

import com.bablsoft.accessflow.ai.api.ApprovalPredictionService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clustered-safe scheduled driver for approval-outcome model retraining (AF-652). Every
 * {@code accessflow.ai.approval-prediction.retrain-poll-interval} it retrains each organization's
 * model over that org's decided-query history and refreshes the row with the quality metrics that
 * decide whether the model may serve.
 *
 * <p>Thin by design. The organization loop, the per-org {@code RuntimeException} swallowing and the
 * feature's {@code enabled} master switch live in {@link ApprovalPredictionService#trainAll()}, and
 * the per-organization transaction sits one level further down, on the {@code @Transactional}
 * training service {@code trainAll()} calls across the proxy. The {@code catch} here is not
 * redundant with that: paging the organization list happens outside {@code trainAll()}'s per-org
 * {@code try}, so a repository failure there would otherwise escape into the scheduler.
 *
 * <p>{@code lockAtMostFor} matches the only other daily job rather than a measured runtime: the
 * batch is O(organizations), so its worst case is unbounded by anything in this repository, and the
 * next tick is a day away — so an over-long lock costs nothing on the node-death path, while a lock
 * that expires mid-run lets a second replica start a concurrent retrain and lose the
 * {@code @Version} race on every model row it touches.
 *
 * <p>Two behaviours worth knowing, both shared with every other job here. A {@code fixedDelay} timer
 * is per-replica and starts at context refresh, so an N-replica cluster retrains up to N times a day
 * and every pod start triggers a pass. That is load, not incorrectness: training is deterministic,
 * so a redundant pass over unchanged history rewrites each row with the model it already held.
 */
@Component
@RequiredArgsConstructor
public class ApprovalPredictionTrainingJob {

    private static final Logger log = LoggerFactory.getLogger(ApprovalPredictionTrainingJob.class);

    private final ApprovalPredictionService approvalPredictionService;

    @Scheduled(fixedDelayString = "${accessflow.ai.approval-prediction.retrain-poll-interval:P1D}")
    @SchedulerLock(name = "approvalPredictionTrainingJob", lockAtMostFor = "PT2H",
            lockAtLeastFor = "PT1M")
    public void run() {
        try {
            approvalPredictionService.trainAll();
        } catch (RuntimeException ex) {
            log.error("Approval prediction retrain run failed", ex);
        }
    }
}
