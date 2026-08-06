package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.ApprovalOutcomeSample;
import com.bablsoft.accessflow.core.api.ApprovalRateCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Featurizes an organization's decided-query history and splits it into train and holdout halves
 * (issue AF-651). Deliberately free of I/O and of any dependency other than the extractor, because
 * the two things it does are the subtle ones in this feature and both need mock-free tests.
 *
 * <p><strong>1. Point-in-time approval rates.</strong> {@code submitter_approval_rate} and
 * {@code datasource_approval_rate} are computed from an expanding window: sample <em>i</em> is
 * featurized with the counts of every <em>strictly earlier</em> sample only, and is folded into the
 * counters afterwards.
 *
 * <p>The obvious alternative — take the full-population counts from
 * {@code ApprovalOutcomeHistoryLookupService} and subtract the sample's own contribution
 * ("leave-one-out") — satisfies the letter of {@link ApprovalFeatureExtractor}'s caller contract and
 * is <em>worse than doing nothing</em>. With the Laplace-smoothed rate
 * {@code (approved + 1) / (decided + 2)}, leaving sample <em>i</em> out of a population of
 * {@code A} approved / {@code D} decided yields {@code A / (D + 1)} when its label is positive and
 * {@code (A + 1) / (D + 1)} when it is negative. Those differ by exactly {@code 1 / (D + 1)} as a
 * deterministic function of the label, so after standardization the feature is an inverse label
 * predictor — perfectly separating within one subject's {@code (A, D)}, and merely inflated across
 * subjects whose counts differ. The trainer puts a large weight on it, holdout AUC is overstated, the
 * {@code min-auc-to-serve} gate waves the model through — and at serving time the feature takes the
 * unconditional middle value {@code (A + 1) / (D + 2)}, which carries no label information at all.
 * Leave-one-out therefore defeats the quality gate, which is this feature's only safety mechanism.
 *
 * <p>Known, accepted divergences from the counts the serving path reads out of the database:
 * <ul>
 *   <li>The oldest samples see near-empty counters (rate → 0.5) while serving always sees the full
 *       lookback window. This is the honest trade — a submitter with no history reads 0.5 at both
 *       train and serve time.</li>
 *   <li>{@code findDecidedSamples} caps at {@code maxRows} while the count queries do not, so an
 *       organization past the cap trains on counts drawn from the newest slice only.</li>
 *   <li>The rolling {@code since} window moves between the training run and any later scoring.</li>
 * </ul>
 * The population <em>predicate</em> is identical either way — the count queries reuse
 * {@code QueryRequestRepository.APPROVAL_OUTCOME_DECIDED_PREDICATE} verbatim.
 *
 * <p>Note the consequence for the quality gate: holdout AUC is measured on the point-in-time
 * distribution, which is not quite the full-window one the serving path sees, so the gate certifies
 * something slightly narrower than what it guards. That is the standard cost of an honest
 * point-in-time feature, and far cheaper than the contaminated alternative above.
 *
 * <p><strong>2. Deterministic holdout split.</strong> The bucket is derived from the query UUID, so
 * a sample lands in the same half on every run and there is no RNG anywhere in training.
 */
@Component
@RequiredArgsConstructor
class ApprovalTrainingSetBuilder {

    /**
     * Percent granularity, so a fraction like {@code 0.15} is representable. Ten buckets would
     * quantise every fraction to a multiple of 0.1 and silently round it down.
     */
    static final int SPLIT_BUCKETS = 100;

    private static final double[][] NO_FEATURES = new double[0][];
    private static final boolean[] NO_LABELS = new boolean[0];

    private final ApprovalFeatureExtractor featureExtractor;

    /**
     * Featurizes {@code samples} and partitions them. The input arrives newest-first from
     * {@code findDecidedSamples}; it is re-sorted ascending by {@code (createdAt, queryRequestId)}
     * here, because that query has no tie-breaker and the expanding-window walk has to run oldest
     * first and be reproducible.
     */
    ApprovalTrainingSet build(List<ApprovalOutcomeSample> samples, double holdoutFraction) {
        if (samples == null || samples.isEmpty()) {
            return new ApprovalTrainingSet(NO_FEATURES, NO_LABELS, NO_FEATURES, NO_LABELS, 0, 0, 0);
        }
        var ordered = samples.stream()
                .sorted(Comparator.comparing(ApprovalOutcomeSample::createdAt)
                        .thenComparing(ApprovalOutcomeSample::queryRequestId))
                .toList();

        Map<UUID, long[]> submitterCounters = new HashMap<>();
        Map<UUID, long[]> datasourceCounters = new HashMap<>();
        var trainFeatures = new ArrayList<double[]>();
        var trainLabels = new ArrayList<Boolean>();
        var holdoutFeatures = new ArrayList<double[]>();
        var holdoutLabels = new ArrayList<Boolean>();
        int positives = 0;

        for (var sample : ordered) {
            var vector = featureExtractor.extract(sample,
                    counts(submitterCounters, sample.submitterId()),
                    counts(datasourceCounters, sample.datasourceId()));
            if (isHoldout(sample.queryRequestId(), holdoutFraction)) {
                holdoutFeatures.add(vector.values());
                holdoutLabels.add(sample.approved());
            } else {
                trainFeatures.add(vector.values());
                trainLabels.add(sample.approved());
            }
            if (sample.approved()) {
                positives++;
            }
            fold(submitterCounters, sample.submitterId(), sample.approved());
            fold(datasourceCounters, sample.datasourceId(), sample.approved());
        }

        return new ApprovalTrainingSet(
                toMatrix(trainFeatures), toLabels(trainLabels),
                toMatrix(holdoutFeatures), toLabels(holdoutLabels),
                ordered.size(), positives, ordered.size() - positives);
    }

    /**
     * Whether a sample belongs to the holdout half. Uses {@code floorMod} rather than
     * {@code Math.abs(...) % n}, which stays negative for {@code Integer.MIN_VALUE}, and rounds the
     * fraction to whole buckets so no fraction is lost to binary representation.
     */
    static boolean isHoldout(UUID queryRequestId, double holdoutFraction) {
        long threshold = Math.round(holdoutFraction * SPLIT_BUCKETS);
        return Math.floorMod(queryRequestId.hashCode(), SPLIT_BUCKETS) < threshold;
    }

    /** {@code null} for a subject with no earlier decided query — the extractor reads that as 0.5. */
    private static ApprovalRateCounts counts(Map<UUID, long[]> counters, UUID subjectId) {
        var counter = counters.get(subjectId);
        return counter == null ? null : new ApprovalRateCounts(counter[0], counter[1]);
    }

    private static void fold(Map<UUID, long[]> counters, UUID subjectId, boolean approved) {
        var counter = counters.computeIfAbsent(subjectId, key -> new long[2]);
        counter[0]++;
        if (approved) {
            counter[1]++;
        }
    }

    private static double[][] toMatrix(List<double[]> rows) {
        return rows.toArray(double[][]::new);
    }

    private static boolean[] toLabels(List<Boolean> labels) {
        var out = new boolean[labels.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = labels.get(i);
        }
        return out;
    }
}
