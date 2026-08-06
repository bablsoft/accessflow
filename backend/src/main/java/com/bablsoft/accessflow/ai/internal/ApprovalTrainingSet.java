package com.bablsoft.accessflow.ai.internal;

/**
 * One organization's featurized training data, already split into the train and holdout halves
 * (issue AF-651). Pure carrier assembled by {@link ApprovalTrainingSetBuilder}.
 *
 * <p>{@code totalSamples} / {@code positiveSamples} / {@code negativeSamples} describe the whole
 * labelled population, <em>not</em> the train split — the quality gate's minimum-sample and
 * per-class checks read the total, and that is also what {@code approval_prediction_model} reports
 * to an admin.
 */
record ApprovalTrainingSet(
        double[][] trainFeatures,
        boolean[] trainLabels,
        double[][] holdoutFeatures,
        boolean[] holdoutLabels,
        int totalSamples,
        int positiveSamples,
        int negativeSamples) {
}
