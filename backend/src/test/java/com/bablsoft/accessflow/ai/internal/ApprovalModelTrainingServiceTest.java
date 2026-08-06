package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.ApprovalPredictionProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.ApprovalPredictionModelEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.ApprovalPredictionModelRepository;
import com.bablsoft.accessflow.core.api.ApprovalOutcomeHistoryLookupService;
import com.bablsoft.accessflow.core.api.ApprovalOutcomeSample;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalModelTrainingServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final Duration LOOKBACK = Duration.ofDays(180);

    @Mock ApprovalOutcomeHistoryLookupService historyLookupService;
    @Mock LogisticRegressionTrainer trainer;
    @Mock ModelEvaluator modelEvaluator;
    @Mock ApprovalPredictionModelRepository modelRepository;

    private final UUID organizationId = UUID.randomUUID();

    ApprovalModelTrainingService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalModelTrainingService(historyLookupService,
                new ApprovalTrainingSetBuilder(new ApprovalFeatureExtractor()), trainer,
                modelEvaluator, modelRepository,
                new ApprovalPredictionProperties(true, Duration.ofDays(1), 50, LOOKBACK, 0.2, 0.65,
                        0.01, 500),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Ids are derived from a fixed seed so the deterministic holdout split is reproducible across
     * runs, and the labels alternate so neither half ends up single-class.
     */
    private static List<ApprovalOutcomeSample> balanced(int perClass) {
        var out = new ArrayList<ApprovalOutcomeSample>();
        for (int i = 0; i < perClass * 2; i++) {
            out.add(new ApprovalOutcomeSample(
                    UUID.nameUUIDFromBytes(("balanced-" + i).getBytes()),
                    QueryType.SELECT, false, NOW.minusSeconds(i * 60L), UUID.randomUUID(),
                    UUID.randomUUID(), i % 2 == 0 ? 10 : 90, null, null, true, null, null, null,
                    null, true, i % 2 == 0));
        }
        return out;
    }

    private static TrainedApprovalModel model() {
        var names = ApprovalFeatureVector.FEATURE_SCHEMA_V1;
        var weights = new java.util.HashMap<String, Double>();
        var means = new java.util.HashMap<String, Double>();
        var stddevs = new java.util.HashMap<String, Double>();
        for (var name : names) {
            weights.put(name, 0.1);
            means.put(name, 0.0);
            stddevs.put(name, 1.0);
        }
        return new TrainedApprovalModel("v1", names, 0.0, weights, means, stddevs);
    }

    private void stubSamples(List<ApprovalOutcomeSample> samples) {
        when(historyLookupService.findDecidedSamples(eq(organizationId), any(), anyInt()))
                .thenReturn(samples);
        when(modelRepository.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
    }

    private ApprovalPredictionModelEntity captureSaved() {
        var captor = ArgumentCaptor.forClass(ApprovalPredictionModelEntity.class);
        verify(modelRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void trainForOrganizationQueriesTheLookbackWindowAndTheRowCap() {
        stubSamples(List.of());

        service.trainForOrganization(organizationId);

        verify(historyLookupService).findDecidedSamples(organizationId,
                Instant.parse("2026-02-07T12:00:00Z"),
                ApprovalModelTrainingService.MAX_TRAINING_ROWS);
    }

    @Test
    void trainForOrganizationStoresANonServingRowBelowTheSampleFloor() {
        stubSamples(balanced(12));

        service.trainForOrganization(organizationId);

        var saved = captureSaved();
        assertThat(saved.isServing()).isFalse();
        assertThat(saved.getTrainingSamples()).isEqualTo(24);
        assertThat(saved.getPositiveSamples()).isEqualTo(12);
        assertThat(saved.getCoefficients()).isEqualTo("{}");
        assertThat(saved.getAuc()).isNull();
        assertThat(saved.getAccuracy()).isNull();
        assertThat(saved.getFeatureSchemaVersion())
                .isEqualTo(ApprovalFeatureVector.SCHEMA_VERSION);
        assertThat(saved.getTrainedAt()).isEqualTo(NOW);
        verifyNoInteractions(trainer, modelEvaluator);
    }

    @Test
    void trainForOrganizationStoresANonServingRowWhenAClassIsTooSmall() {
        var skewed = new ArrayList<>(balanced(30));
        // Rewrite all but 5 negatives into positives, keeping the total above the sample floor.
        for (int i = 0; i < skewed.size(); i++) {
            var sample = skewed.get(i);
            if (!sample.approved() && i > 10) {
                skewed.set(i, approve(sample));
            }
        }
        stubSamples(skewed);

        service.trainForOrganization(organizationId);

        var saved = captureSaved();
        assertThat(saved.isServing()).isFalse();
        assertThat(saved.getTrainingSamples()).isEqualTo(60);
        assertThat(saved.getCoefficients()).isEqualTo("{}");
        verifyNoInteractions(trainer, modelEvaluator);
    }

    private static ApprovalOutcomeSample approve(ApprovalOutcomeSample sample) {
        return new ApprovalOutcomeSample(sample.queryRequestId(), sample.queryType(),
                sample.transactional(), sample.createdAt(), sample.submitterId(),
                sample.datasourceId(), sample.aiRiskScore(), sample.aiRiskLevel(),
                sample.aiIssueCount(), sample.aiMissing(), sample.estimatedRows(),
                sample.affectedRowCount(), sample.estimatedCost(), sample.scanType(),
                sample.estimateMissing(), true);
    }

    @Test
    void trainForOrganizationStoresANonServingRowWhenTheHoldoutAucIsTooLow() {
        stubSamples(balanced(30));
        when(trainer.train(any(), any(), any(), anyDouble(), anyInt())).thenReturn(model());
        when(modelEvaluator.calculateAuc(any(), any())).thenReturn(0.61);
        when(modelEvaluator.calculateAccuracy(any(), any(), anyDouble())).thenReturn(0.55);

        service.trainForOrganization(organizationId);

        var saved = captureSaved();
        assertThat(saved.isServing()).isFalse();
        assertThat(saved.getAuc()).isEqualTo(0.61);
        assertThat(saved.getAccuracy()).isEqualTo(0.55);
        // Coefficients are still stored so an admin can see the model was trained, just gated.
        assertThat(saved.getCoefficients()).contains("submitter_approval_rate");
    }

    @Test
    void trainForOrganizationServesWhenTheHoldoutAucClearsTheGate() {
        stubSamples(balanced(30));
        when(trainer.train(any(), any(), any(), anyDouble(), anyInt())).thenReturn(model());
        when(modelEvaluator.calculateAuc(any(), any())).thenReturn(0.65);
        when(modelEvaluator.calculateAccuracy(any(), any(), anyDouble())).thenReturn(0.71);

        service.trainForOrganization(organizationId);

        var saved = captureSaved();
        assertThat(saved.isServing()).isTrue();
        assertThat(saved.getAuc()).isEqualTo(0.65);
        assertThat(saved.getAccuracy()).isEqualTo(0.71);
        assertThat(saved.getTrainingSamples()).isEqualTo(60);
        assertThat(saved.getPositiveSamples()).isEqualTo(30);
        assertThat(saved.getTrainedAt()).isEqualTo(NOW);
        verify(trainer).train(any(), any(), eq(ApprovalFeatureVector.FEATURE_SCHEMA_V1),
                eq(0.01), eq(500));
    }

    @Test
    void trainForOrganizationLeavesTheMetricsNullWhenTheHoldoutIsSingleClass() {
        // Every sample in bucket >= 20 is a train row; force the holdout to one class by making
        // every sample positive except the ones the split keeps for training.
        var oneClassHoldout = new ArrayList<ApprovalOutcomeSample>();
        for (int i = 0; i < 60; i++) {
            var id = UUID.nameUUIDFromBytes(("holdout-" + i).getBytes());
            boolean approved = ApprovalTrainingSetBuilder.isHoldout(id, 0.2) || i % 2 == 0;
            oneClassHoldout.add(new ApprovalOutcomeSample(id, QueryType.SELECT, false,
                    NOW.minusSeconds(i * 60L), UUID.randomUUID(), UUID.randomUUID(),
                    approved ? 10 : 90, null, null, true, null, null, null, null, true, approved));
        }
        stubSamples(oneClassHoldout);
        when(trainer.train(any(), any(), any(), anyDouble(), anyInt())).thenReturn(model());

        service.trainForOrganization(organizationId);

        var saved = captureSaved();
        assertThat(saved.getAuc()).isNull();
        assertThat(saved.getAccuracy()).isNull();
        assertThat(saved.isServing()).isFalse();
        verify(modelEvaluator, never()).calculateAuc(any(), any());
        verify(modelEvaluator, never()).calculateAccuracy(any(), any(), anyDouble());
    }

    @Test
    void trainForOrganizationCreatesANewRowWithTheOrganizationIdAndCreatedAt() {
        stubSamples(List.of());

        service.trainForOrganization(organizationId);

        var saved = captureSaved();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOrganizationId()).isEqualTo(organizationId);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void trainForOrganizationReusesTheExistingRowPreservingItsIdentity() {
        var existing = new ApprovalPredictionModelEntity();
        var existingId = UUID.randomUUID();
        var createdAt = Instant.parse("2026-01-01T00:00:00Z");
        existing.setId(existingId);
        existing.setOrganizationId(organizationId);
        existing.setCreatedAt(createdAt);
        existing.setServing(true);
        when(historyLookupService.findDecidedSamples(eq(organizationId), any(), anyInt()))
                .thenReturn(List.of());
        when(modelRepository.findByOrganizationId(organizationId))
                .thenReturn(Optional.of(existing));

        service.trainForOrganization(organizationId);

        var saved = captureSaved();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getId()).isEqualTo(existingId);
        assertThat(saved.getCreatedAt()).isEqualTo(createdAt);
        assertThat(saved.isServing()).isFalse();
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void trainForOrganizationStoresANonServingRowWhenTheSplitLeavesNoTrainingRows() {
        // holdoutFraction is clamped to (0,1) by the properties record, so the only way to reach
        // the empty-train-split guard is a population whose every id lands in the holdout bucket.
        var allHoldout = new ArrayList<ApprovalOutcomeSample>();
        for (int i = 0; allHoldout.size() < 60; i++) {
            var id = UUID.nameUUIDFromBytes(("all-holdout-" + i).getBytes());
            if (ApprovalTrainingSetBuilder.isHoldout(id, 0.2)) {
                allHoldout.add(new ApprovalOutcomeSample(id, QueryType.SELECT, false,
                        NOW.minusSeconds(i * 60L), UUID.randomUUID(), UUID.randomUUID(), 10, null,
                        null, true, null, null, null, null, true, allHoldout.size() % 2 == 0));
            }
        }
        stubSamples(allHoldout);

        service.trainForOrganization(organizationId);

        var saved = captureSaved();
        assertThat(saved.isServing()).isFalse();
        assertThat(saved.getCoefficients()).isEqualTo("{}");
        verifyNoInteractions(trainer, modelEvaluator);
    }
}
