package com.bablsoft.accessflow.ai.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for approval-outcome prediction (AF-645), bound from
 * {@code accessflow.ai.approval-prediction.*}. They drive the daily retrain over each org's
 * decided-query history and the quality gate deciding whether the trained model may serve
 * advisory predictions.
 *
 * <ul>
 *   <li>{@code enabled} — master switch for the whole feature (advisory and cheap, hence on by
 *       default); default {@code true}.</li>
 *   <li>{@code retrainPollInterval} — cadence of the scheduled retrain; default {@code P1D}.</li>
 *   <li>{@code minTrainingSamples} — decided queries an org must have before a model is trained
 *       (cold-start guard); default 50.</li>
 *   <li>{@code trainingLookback} — how far back training data reaches; default {@code P180D}.</li>
 *   <li>{@code holdoutFraction} — fraction of samples held out for evaluation; default 0.2.</li>
 *   <li>{@code minAucToServe} — holdout AUC below which the model never serves; default 0.65.</li>
 *   <li>{@code l2Lambda} — L2 regularization strength (0 disables regularization); default 0.01.</li>
 *   <li>{@code maxIterations} — gradient-descent iteration cap; default 500.</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.ai.approval-prediction")
public record ApprovalPredictionProperties(
        Boolean enabled,
        Duration retrainPollInterval,
        int minTrainingSamples,
        Duration trainingLookback,
        double holdoutFraction,
        double minAucToServe,
        double l2Lambda,
        int maxIterations) {

    public ApprovalPredictionProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (retrainPollInterval == null || retrainPollInterval.isZero() || retrainPollInterval.isNegative()) {
            retrainPollInterval = Duration.ofDays(1);
        }
        if (minTrainingSamples <= 0) {
            minTrainingSamples = 50;
        }
        if (trainingLookback == null || trainingLookback.isZero() || trainingLookback.isNegative()) {
            trainingLookback = Duration.ofDays(180);
        }
        if (holdoutFraction <= 0 || holdoutFraction >= 1) {
            holdoutFraction = 0.2;
        }
        if (minAucToServe <= 0 || minAucToServe > 1) {
            minAucToServe = 0.65;
        }
        if (l2Lambda < 0) {
            l2Lambda = 0.01;
        }
        if (maxIterations <= 0) {
            maxIterations = 500;
        }
    }
}
