package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.ApprovalPredictionProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.ApprovalPredictionModelEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.ApprovalPredictionModelRepository;
import com.bablsoft.accessflow.core.api.ApprovalOutcomeHistoryLookupService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Trains one organization's approval-outcome model and stores it with the quality metrics that
 * decide whether it may serve (issue AF-651, per the AF-652 training spec).
 *
 * <p><strong>Why this is its own bean.</strong> {@code trainAll()} lives in
 * {@link DefaultApprovalPredictionService} and calls in from outside, so the proxy applies and each
 * organization gets its own transaction — one bad organization rolls back only its own row. Were
 * both methods on the same bean, self-invocation would bypass the proxy: the read and the write
 * would land in separate transactions, the entity would be detached by the time {@code save} ran,
 * and the {@code @Version} comparison would start failing under concurrency.
 */
@Service
@RequiredArgsConstructor
class ApprovalModelTrainingService {

    /**
     * Safety cap on the training window. A constant rather than a property: a knob owes
     * {@code docs/09-deployment.md} a row, and no realistic organization decides 20 000 queries
     * inside the lookback window.
     */
    static final int MAX_TRAINING_ROWS = 20_000;

    /** Per-class floor. A model trained on 49 approvals and 1 rejection learns "always approve". */
    static final int MIN_SAMPLES_PER_CLASS = 10;

    /** The {@code coefficients} column is NOT NULL, so a gated (non-serving) row still needs a value. */
    private static final String EMPTY_COEFFICIENTS = "{}";

    /** Decision threshold for holdout accuracy — the neutral midpoint, not a tuned operating point. */
    private static final double ACCURACY_THRESHOLD = 0.5;

    private static final Logger log = LoggerFactory.getLogger(ApprovalModelTrainingService.class);

    private final ApprovalOutcomeHistoryLookupService approvalOutcomeHistoryLookupService;
    private final ApprovalTrainingSetBuilder trainingSetBuilder;
    private final LogisticRegressionTrainer trainer;
    private final ModelEvaluator modelEvaluator;
    private final ApprovalPredictionModelRepository modelRepository;
    private final ApprovalPredictionProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Fetches the organization's decided history, trains, evaluates on the deterministic holdout,
     * and upserts the model row. Read, train and write share one transaction so the entity stays
     * managed across the whole sequence.
     *
     * <p>Every exit writes a row. Below the sample-count or per-class gate the row records the
     * counts with {@code serving=false} and empty coefficients, so an admin can tell "not enough
     * history yet" from "trained but not good enough".
     */
    @Transactional
    public void trainForOrganization(UUID organizationId) {
        var now = clock.instant();
        var samples = approvalOutcomeHistoryLookupService.findDecidedSamples(
                organizationId, now.minus(properties.trainingLookback()), MAX_TRAINING_ROWS);
        var trainingSet = trainingSetBuilder.build(samples, properties.holdoutFraction());

        if (trainingSet.totalSamples() < properties.minTrainingSamples()) {
            log.info("Approval model for org {} not trained: {} decided samples, {} required",
                    organizationId, trainingSet.totalSamples(), properties.minTrainingSamples());
            storeNotServing(organizationId, trainingSet, now);
            return;
        }
        if (trainingSet.positiveSamples() < MIN_SAMPLES_PER_CLASS
                || trainingSet.negativeSamples() < MIN_SAMPLES_PER_CLASS) {
            log.info("Approval model for org {} not trained: class imbalance ({} approved, "
                            + "{} rejected, {} required per class)",
                    organizationId, trainingSet.positiveSamples(), trainingSet.negativeSamples(),
                    MIN_SAMPLES_PER_CLASS);
            storeNotServing(organizationId, trainingSet, now);
            return;
        }
        if (trainingSet.trainFeatures().length == 0) {
            log.warn("Approval model for org {} not trained: holdout split left no training rows",
                    organizationId);
            storeNotServing(organizationId, trainingSet, now);
            return;
        }

        var model = trainer.train(trainingSet.trainFeatures(), trainingSet.trainLabels(),
                ApprovalFeatureVector.FEATURE_SCHEMA_V1, properties.l2Lambda(),
                properties.maxIterations());

        Double auc = null;
        Double accuracy = null;
        if (hasBothClasses(trainingSet.holdoutLabels())) {
            var probabilities = score(model, trainingSet.holdoutFeatures());
            auc = modelEvaluator.calculateAuc(probabilities, trainingSet.holdoutLabels());
            accuracy = modelEvaluator.calculateAccuracy(
                    probabilities, trainingSet.holdoutLabels(), ACCURACY_THRESHOLD);
        }
        boolean serving = auc != null && auc >= properties.minAucToServe();
        log.info("Trained approval model for org {}: {} samples ({} approved), auc={}, serving={}",
                organizationId, trainingSet.totalSamples(), trainingSet.positiveSamples(), auc,
                serving);
        upsert(organizationId, model.toJson(objectMapper), trainingSet.totalSamples(),
                trainingSet.positiveSamples(), auc, accuracy, serving, now);
    }

    /**
     * An empty or single-class holdout carries no ranking information.
     * {@code ModelEvaluator.calculateAuc} returns {@code 0.5} in that case, which is
     * indistinguishable from a genuinely useless model — so the metric is left {@code null} instead
     * and the gate fails closed.
     */
    private static boolean hasBothClasses(boolean[] labels) {
        boolean positive = false;
        boolean negative = false;
        for (boolean label : labels) {
            positive |= label;
            negative |= !label;
        }
        return positive && negative;
    }

    private static double[] score(TrainedApprovalModel model, double[][] features) {
        var probabilities = new double[features.length];
        for (int i = 0; i < features.length; i++) {
            probabilities[i] = model.predict(features[i]);
        }
        return probabilities;
    }

    private void storeNotServing(UUID organizationId, ApprovalTrainingSet trainingSet, Instant now) {
        upsert(organizationId, EMPTY_COEFFICIENTS, trainingSet.totalSamples(),
                trainingSet.positiveSamples(), null, null, false, now);
    }

    private void upsert(UUID organizationId, String coefficientsJson, int totalSamples,
                        int positiveSamples, Double auc, Double accuracy, boolean serving,
                        Instant now) {
        var entity = modelRepository.findByOrganizationId(organizationId)
                .orElseGet(() -> newModel(organizationId, now));
        entity.setFeatureSchemaVersion(ApprovalFeatureVector.SCHEMA_VERSION);
        entity.setCoefficients(coefficientsJson);
        entity.setTrainingSamples(totalSamples);
        entity.setPositiveSamples(positiveSamples);
        entity.setAuc(auc);
        entity.setAccuracy(accuracy);
        entity.setServing(serving);
        entity.setTrainedAt(now);
        // Only takes effect on insert — the entity's @PreUpdate overwrites updated_at with wall-clock
        // time on the update path. trained_at is the column the Clock actually governs.
        entity.setUpdatedAt(now);
        modelRepository.save(entity);
    }

    private static ApprovalPredictionModelEntity newModel(UUID organizationId, Instant now) {
        var entity = new ApprovalPredictionModelEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setCreatedAt(now);
        return entity;
    }
}
