package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.ApprovalOutcomeSample;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalTrainingSetBuilderTest {

    private static final Instant EPOCH = Instant.parse("2026-07-01T10:00:00Z");
    private static final int SUBMITTER_RATE =
            ApprovalFeatureVector.FEATURE_SCHEMA_V1.indexOf("submitter_approval_rate");
    private static final int DATASOURCE_RATE =
            ApprovalFeatureVector.FEATURE_SCHEMA_V1.indexOf("datasource_approval_rate");

    private final ApprovalTrainingSetBuilder builder =
            new ApprovalTrainingSetBuilder(new ApprovalFeatureExtractor());

    private final UUID submitterId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    private ApprovalOutcomeSample sample(UUID id, int minutesAfterEpoch, boolean approved) {
        return sample(id, minutesAfterEpoch, approved, submitterId, datasourceId);
    }

    private static ApprovalOutcomeSample sample(UUID id, int minutesAfterEpoch, boolean approved,
                                                UUID submitterId, UUID datasourceId) {
        return new ApprovalOutcomeSample(id, QueryType.SELECT, false,
                EPOCH.plusSeconds(minutesAfterEpoch * 60L), submitterId, datasourceId,
                40, null, null, true, null, null, null, null, true, approved);
    }

    /** Ids whose hash bucket is >= 20, so a 0.2 fraction keeps every sample in the train split. */
    private static List<UUID> trainOnlyIds(int count) {
        var ids = new ArrayList<UUID>(count);
        for (int seed = 0; ids.size() < count; seed++) {
            var candidate = UUID.nameUUIDFromBytes(("train-" + seed).getBytes());
            if (!ApprovalTrainingSetBuilder.isHoldout(candidate, 0.2)) {
                ids.add(candidate);
            }
        }
        return ids;
    }

    @Test
    void buildComputesApprovalRatesFromStrictlyEarlierSamplesOnly() {
        var ids = trainOnlyIds(4);
        // Ascending in time: approved, rejected, approved, rejected.
        var samples = List.of(
                sample(ids.get(0), 0, true),
                sample(ids.get(1), 1, false),
                sample(ids.get(2), 2, true),
                sample(ids.get(3), 3, false));

        var set = builder.build(samples, 0.2);

        assertThat(set.trainFeatures())
                .hasDimensions(4, ApprovalFeatureVector.FEATURE_SCHEMA_V1.size());
        // (approved + 1) / (decided + 2) over the samples seen so far, sample itself excluded.
        assertThat(set.trainFeatures()[0][SUBMITTER_RATE]).isEqualTo(0.5);           // 0 of 0
        assertThat(set.trainFeatures()[1][SUBMITTER_RATE]).isEqualTo(2.0 / 3.0);     // 1 of 1
        assertThat(set.trainFeatures()[2][SUBMITTER_RATE]).isEqualTo(2.0 / 4.0);     // 1 of 2
        assertThat(set.trainFeatures()[3][SUBMITTER_RATE]).isEqualTo(3.0 / 5.0);     // 2 of 3
        assertThat(set.trainFeatures()[0][DATASOURCE_RATE]).isEqualTo(0.5);
        assertThat(set.trainFeatures()[3][DATASOURCE_RATE]).isEqualTo(3.0 / 5.0);
    }

    @Test
    void buildNeverFoldsASampleIntoItsOwnRate() {
        var ids = trainOnlyIds(1);

        var approved = builder.build(List.of(sample(ids.get(0), 0, true)), 0.2);
        var rejected = builder.build(List.of(sample(ids.get(0), 0, false)), 0.2);

        assertThat(approved.trainFeatures()[0][SUBMITTER_RATE]).isEqualTo(0.5);
        assertThat(rejected.trainFeatures()[0][SUBMITTER_RATE]).isEqualTo(0.5);
    }

    /**
     * The regression guard for the leakage defect: a leave-one-out implementation makes a sample's
     * own rate feature a function of its own label, which is a perfect inverse label predictor and
     * silently defeats the holdout-AUC quality gate. Flipping one label must leave that sample's own
     * rate untouched.
     */
    @Test
    void buildKeepsASamplesOwnRateUnchangedWhenItsLabelFlips() {
        var ids = trainOnlyIds(3);
        var base = new ArrayList<ApprovalOutcomeSample>(List.of(
                sample(ids.get(0), 0, true),
                sample(ids.get(1), 1, false),
                sample(ids.get(2), 2, true)));
        var flipped = new ArrayList<>(base);
        flipped.set(2, sample(ids.get(2), 2, false));

        var withApproved = builder.build(base, 0.2);
        var withRejected = builder.build(flipped, 0.2);

        assertThat(withRejected.trainFeatures()[2][SUBMITTER_RATE])
                .isEqualTo(withApproved.trainFeatures()[2][SUBMITTER_RATE]);
        assertThat(withRejected.trainFeatures()[2][DATASOURCE_RATE])
                .isEqualTo(withApproved.trainFeatures()[2][DATASOURCE_RATE]);
    }

    @Test
    void buildTracksEachSubjectSeparately() {
        var ids = trainOnlyIds(3);
        var otherSubmitter = UUID.randomUUID();
        var samples = List.of(
                sample(ids.get(0), 0, true, submitterId, datasourceId),
                sample(ids.get(1), 1, true, submitterId, datasourceId),
                sample(ids.get(2), 2, false, otherSubmitter, datasourceId));

        var set = builder.build(samples, 0.2);

        // The third sample is the other submitter's first, but the datasource's third.
        assertThat(set.trainFeatures()[2][SUBMITTER_RATE]).isEqualTo(0.5);
        assertThat(set.trainFeatures()[2][DATASOURCE_RATE]).isEqualTo(3.0 / 4.0);
    }

    @Test
    void buildIsInsensitiveToTheInputOrderIncludingCreatedAtTies() {
        var ids = trainOnlyIds(3);
        var ascending = List.of(
                sample(ids.get(0), 0, true),
                sample(ids.get(1), 5, false),
                sample(ids.get(2), 5, true));

        var forwards = builder.build(ascending, 0.2);
        var backwards = builder.build(ascending.reversed(), 0.2);

        assertThat(backwards.trainFeatures()).isDeepEqualTo(forwards.trainFeatures());
        assertThat(backwards.trainLabels()).containsExactly(forwards.trainLabels());
    }

    @Test
    void buildCountsTheWholeLabelledPopulationNotJustTheTrainSplit() {
        var samples = new ArrayList<ApprovalOutcomeSample>();
        for (int i = 0; i < 40; i++) {
            samples.add(sample(UUID.nameUUIDFromBytes(("pop-" + i).getBytes()), i, i % 4 != 0));
        }

        var set = builder.build(samples, 0.2);

        assertThat(set.totalSamples()).isEqualTo(40);
        assertThat(set.positiveSamples()).isEqualTo(30);
        assertThat(set.negativeSamples()).isEqualTo(10);
        assertThat(set.trainFeatures().length + set.holdoutFeatures().length).isEqualTo(40);
        assertThat(set.trainLabels()).hasSize(set.trainFeatures().length);
        assertThat(set.holdoutLabels()).hasSize(set.holdoutFeatures().length);
    }

    @Test
    void buildReturnsAnEmptySetForNoSamples() {
        var set = builder.build(List.of(), 0.2);

        assertThat(set.totalSamples()).isZero();
        assertThat(set.positiveSamples()).isZero();
        assertThat(set.negativeSamples()).isZero();
        assertThat(set.trainFeatures()).isEmpty();
        assertThat(set.holdoutFeatures()).isEmpty();
        assertThat(set.trainLabels()).isEmpty();
        assertThat(set.holdoutLabels()).isEmpty();
    }

    @Test
    void buildReturnsAnEmptySetForNullSamples() {
        assertThat(builder.build(null, 0.2).totalSamples()).isZero();
    }

    @Test
    void isHoldoutIsStableForTheSameId() {
        var id = UUID.randomUUID();

        assertThat(ApprovalTrainingSetBuilder.isHoldout(id, 0.2))
                .isEqualTo(ApprovalTrainingSetBuilder.isHoldout(id, 0.2));
    }

    @Test
    void isHoldoutHonoursFractionsThatAreNotMultiplesOfATenth() {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 2000; i++) {
            ids.add(UUID.nameUUIDFromBytes(("fraction-" + i).getBytes()));
        }

        long held = ids.stream().filter(id -> ApprovalTrainingSetBuilder.isHoldout(id, 0.3)).count();

        // A ten-bucket split would quantise 0.3 down to 0.2; assert we are near 30 %, not 20 %.
        assertThat(held / (double) ids.size()).isBetween(0.26, 0.34);
    }

    /**
     * {@code UUID.hashCode} folds the two longs to an int, so a most-significant word of
     * {@code 0x8000_0000_0000_0000} with a zero least-significant word hashes to exactly
     * {@code Integer.MIN_VALUE} — the value {@code Math.abs} cannot negate. A
     * {@code Math.abs(hash) % 100} split would yield {@code -48} for this id and so put it in the
     * holdout for <em>every</em> fraction; {@code floorMod} yields bucket 52, which a 20 % holdout
     * excludes.
     */
    @Test
    void isHoldoutHandlesTheHashBucketMathAbsCannotNegate() {
        var id = new UUID(0x8000_0000_0000_0000L, 0L);

        assertThat(id.hashCode()).isEqualTo(Integer.MIN_VALUE);
        assertThat(ApprovalTrainingSetBuilder.isHoldout(id, 0.2)).isFalse();
        assertThat(ApprovalTrainingSetBuilder.isHoldout(id, 0.6)).isTrue();
    }
}
