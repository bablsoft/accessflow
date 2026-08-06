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
 * <p>Thin by design. The organization loop, the per-organization transaction boundary, the per-org
 * {@code RuntimeException} swallowing, and the feature's {@code enabled} master switch all live in
 * {@link ApprovalPredictionService#trainAll()}; the {@code catch} here guards the batch as a whole so
 * an unexpected failure is logged rather than thrown out of the scheduler.
 *
 * <p>{@code lockAtMostFor} is generous relative to a realistic run: training is in-process
 * arithmetic over at most 20 000 rows per org, but the lock has to outlive the slowest plausible
 * batch across every organization, since a lock that expires mid-run lets a second replica start a
 * concurrent retrain.
 */
@Component
@RequiredArgsConstructor
public class ApprovalPredictionTrainingJob {

    private static final Logger log = LoggerFactory.getLogger(ApprovalPredictionTrainingJob.class);

    private final ApprovalPredictionService approvalPredictionService;

    @Scheduled(fixedDelayString = "${accessflow.ai.approval-prediction.retrain-poll-interval:P1D}")
    @SchedulerLock(name = "approvalPredictionTrainingJob", lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT30S")
    public void run() {
        try {
            approvalPredictionService.trainAll();
        } catch (RuntimeException ex) {
            log.error("Approval prediction retrain run failed", ex);
        }
    }
}
