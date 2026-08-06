package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.ApprovalPredictionProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.ApprovalPredictionModelEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.ApprovalPredictionModelRepository;
import com.bablsoft.accessflow.core.api.ApprovalPredictionLookupService;
import com.bablsoft.accessflow.core.api.ApprovalPredictionPersistenceService;
import com.bablsoft.accessflow.core.api.ApprovalPredictionSnapshot;
import com.bablsoft.accessflow.core.api.OrganizationAdminService;
import com.bablsoft.accessflow.core.api.OrganizationView;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.PersistApprovalPredictionCommand;
import com.bablsoft.accessflow.core.api.QueryEstimateLookupService;
import com.bablsoft.accessflow.core.api.QueryEstimateSnapshot;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.events.ApprovalPredictionCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultApprovalPredictionServiceTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock QueryEstimateLookupService queryEstimateLookupService;
    @Mock ApprovalPredictionLookupService approvalPredictionLookupService;
    @Mock ApprovalPredictionPersistenceService approvalPredictionPersistenceService;
    @Mock ApprovalPredictionModelRepository modelRepository;
    @Mock ApprovalFeatureLoader featureLoader;
    @Mock ApprovalModelTrainingService trainingService;
    @Mock OrganizationAdminService organizationAdminService;
    @Mock ApplicationEventPublisher eventPublisher;

    private final UUID queryId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID modelId = UUID.randomUUID();
    private final UUID predictionId = UUID.randomUUID();
    private final ObjectMapper objectMapper = new ObjectMapper();

    DefaultApprovalPredictionService service;

    @BeforeEach
    void setUp() {
        service = build(true);
        when(approvalPredictionPersistenceService.persist(any())).thenReturn(predictionId);
    }

    private DefaultApprovalPredictionService build(boolean enabled) {
        return new DefaultApprovalPredictionService(queryRequestLookupService,
                queryEstimateLookupService, approvalPredictionLookupService,
                approvalPredictionPersistenceService, modelRepository, featureLoader,
                trainingService, organizationAdminService,
                new ApprovalPredictionProperties(enabled, Duration.ofDays(1), 50,
                        Duration.ofDays(180), 0.2, 0.65, 0.01, 500),
                objectMapper, eventPublisher);
    }

    private QueryRequestSnapshot snapshot(QueryStatus status) {
        return new QueryRequestSnapshot(queryId, UUID.randomUUID(), organizationId,
                UUID.randomUUID(), "SELECT 1", QueryType.SELECT, false, status, null, null, null,
                false);
    }

    private ApprovalPredictionModelEntity servingModel(int schemaVersion, List<String> names) {
        var weights = new HashMap<String, Double>();
        var means = new HashMap<String, Double>();
        var stddevs = new HashMap<String, Double>();
        for (var name : names) {
            weights.put(name, 0.25);
            means.put(name, 0.0);
            stddevs.put(name, 1.0);
        }
        var trained = new TrainedApprovalModel("v1", names, 0.5, weights, means, stddevs);
        var entity = new ApprovalPredictionModelEntity();
        entity.setId(modelId);
        entity.setOrganizationId(organizationId);
        entity.setFeatureSchemaVersion(schemaVersion);
        entity.setCoefficients(trained.toJson(objectMapper));
        entity.setServing(true);
        return entity;
    }

    private ApprovalPredictionModelEntity servingModel() {
        return servingModel(ApprovalFeatureVector.SCHEMA_VERSION,
                ApprovalFeatureVector.FEATURE_SCHEMA_V1);
    }

    private static ApprovalFeatureVector vector(boolean estimateMissing) {
        var values = new double[ApprovalFeatureVector.FEATURE_SCHEMA_V1.size()];
        values[ApprovalFeatureVector.FEATURE_SCHEMA_V1.indexOf("risk_score")] = 0.42;
        values[ApprovalFeatureVector.FEATURE_SCHEMA_V1.indexOf("estimate_missing")] =
                estimateMissing ? 1.0 : 0.0;
        return new ApprovalFeatureVector(values);
    }

    private PersistApprovalPredictionCommand capturePersisted() {
        var captor = ArgumentCaptor.forClass(PersistApprovalPredictionCommand.class);
        verify(approvalPredictionPersistenceService).persist(captor.capture());
        return captor.getValue();
    }

    private ApprovalPredictionCompletedEvent capturePublished() {
        var captor = ArgumentCaptor.forClass(ApprovalPredictionCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    private void stubServingHappyPath(boolean estimateMissing) {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW)));
        when(modelRepository.findByOrganizationId(organizationId))
                .thenReturn(Optional.of(servingModel()));
        when(featureLoader.load(any())).thenReturn(vector(estimateMissing));
    }

    // ---------------------------------------------------------------- predictForQuery

    @Test
    void predictForQuerySkipsWhenTheFeatureIsDisabled() {
        service = build(false);
        when(approvalPredictionPersistenceService.persist(any())).thenReturn(predictionId);

        service.predictForQuery(queryId);

        var command = capturePersisted();
        assertThat(command.skipped()).isTrue();
        assertThat(command.skippedReason())
                .isEqualTo(DefaultApprovalPredictionService.SKIP_DISABLED);
        assertThat(command.probability()).isNull();
        assertThat(capturePublished())
                .isEqualTo(new ApprovalPredictionCompletedEvent(queryId, predictionId, null));
        verifyNoInteractions(queryRequestLookupService, modelRepository, featureLoader);
    }

    @Test
    void predictForQueryWritesNothingWhenTheQueryIsGone() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        service.predictForQuery(queryId);

        verifyNoInteractions(approvalPredictionPersistenceService, eventPublisher, modelRepository);
    }

    @Test
    void predictForQuerySkipsWhenTheOrgHasNoModelYet() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW)));
        when(modelRepository.findByOrganizationId(organizationId)).thenReturn(Optional.empty());

        service.predictForQuery(queryId);

        var command = capturePersisted();
        assertThat(command.skipped()).isTrue();
        assertThat(command.skippedReason())
                .isEqualTo(DefaultApprovalPredictionService.SKIP_MODEL_NOT_SERVING);
        verifyNoInteractions(featureLoader);
    }

    @Test
    void predictForQuerySkipsWhenTheModelFailedTheQualityGate() {
        var gated = servingModel();
        gated.setServing(false);
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW)));
        when(modelRepository.findByOrganizationId(organizationId)).thenReturn(Optional.of(gated));

        service.predictForQuery(queryId);

        assertThat(capturePersisted().skippedReason())
                .isEqualTo(DefaultApprovalPredictionService.SKIP_MODEL_NOT_SERVING);
        verifyNoInteractions(featureLoader);
    }

    @Test
    void predictForQuerySkipsWhenTheModelWasTrainedOnAnotherFeatureSchema() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW)));
        when(modelRepository.findByOrganizationId(organizationId)).thenReturn(Optional.of(
                servingModel(ApprovalFeatureVector.SCHEMA_VERSION + 1,
                        ApprovalFeatureVector.FEATURE_SCHEMA_V1)));

        service.predictForQuery(queryId);

        assertThat(capturePersisted().skippedReason())
                .isEqualTo(DefaultApprovalPredictionService.SKIP_MODEL_NOT_SERVING);
        // The cheap int gate fires before the coefficients are parsed or features loaded.
        verifyNoInteractions(featureLoader);
    }

    @Test
    void predictForQuerySkipsWhenTheStoredFeatureNamesDoNotMatchTheSchema() {
        var reordered = new java.util.ArrayList<>(ApprovalFeatureVector.FEATURE_SCHEMA_V1);
        java.util.Collections.swap(reordered, 0, 1);
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW)));
        when(modelRepository.findByOrganizationId(organizationId)).thenReturn(Optional.of(
                servingModel(ApprovalFeatureVector.SCHEMA_VERSION, List.copyOf(reordered))));

        service.predictForQuery(queryId);

        assertThat(capturePersisted().skippedReason())
                .isEqualTo(DefaultApprovalPredictionService.SKIP_MODEL_NOT_SERVING);
        verifyNoInteractions(featureLoader);
    }

    @Test
    void predictForQueryPersistsTheProbabilityAndTheFeatureSnapshot() {
        stubServingHappyPath(false);

        service.predictForQuery(queryId);

        var command = capturePersisted();
        assertThat(command.queryRequestId()).isEqualTo(queryId);
        assertThat(command.probability()).isBetween(0.0, 1.0);
        assertThat(command.modelId()).isEqualTo(modelId);
        assertThat(command.featureSchemaVersion())
                .isEqualTo(ApprovalFeatureVector.SCHEMA_VERSION);
        assertThat(command.skipped()).isFalse();
        assertThat(command.failed()).isFalse();
        // A JSON boolean, not 0.0 — the persistence service's replace gate coerces a number to false.
        assertThat(command.featuresJson()).contains("\"estimate_missing\":false");
        assertThat(command.featuresJson()).contains("\"risk_score\":0.42");
        assertThat(capturePublished().probability()).isEqualTo(command.probability());
    }

    @Test
    void predictForQueryRecordsAMissingEstimateAsABooleanTrue() {
        stubServingHappyPath(true);

        service.predictForQuery(queryId);

        assertThat(capturePersisted().featuresJson()).contains("\"estimate_missing\":true");
    }

    @Test
    void predictForQueryWritesNothingWhenTheFeatureLoaderFindsNoDetailRow() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW)));
        when(modelRepository.findByOrganizationId(organizationId))
                .thenReturn(Optional.of(servingModel()));
        when(featureLoader.load(any())).thenReturn(null);

        service.predictForQuery(queryId);

        verifyNoInteractions(approvalPredictionPersistenceService, eventPublisher);
    }

    @Test
    void predictForQueryPersistsAFailedSentinelAndSwallowsTheFailure() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW)));
        when(modelRepository.findByOrganizationId(organizationId))
                .thenReturn(Optional.of(servingModel()));
        when(featureLoader.load(any())).thenThrow(new IllegalStateException("lookup exploded"));

        assertThatCode(() -> service.predictForQuery(queryId)).doesNotThrowAnyException();

        var command = capturePersisted();
        assertThat(command.failed()).isTrue();
        assertThat(command.errorMessage()).isEqualTo("lookup exploded");
        assertThat(command.probability()).isNull();
        assertThat(command.skipped()).isFalse();
    }

    @Test
    void predictForQueryTruncatesAnOverlongFailureMessage() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW)));
        when(modelRepository.findByOrganizationId(organizationId))
                .thenReturn(Optional.of(servingModel()));
        when(featureLoader.load(any())).thenThrow(new IllegalStateException("x".repeat(900)));

        service.predictForQuery(queryId);

        assertThat(capturePersisted().errorMessage()).hasSize(500);
    }

    @Test
    void predictForQueryDoesNotPropagateWhenTheSentinelWriteAlsoFails() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW)));
        when(modelRepository.findByOrganizationId(organizationId))
                .thenReturn(Optional.of(servingModel()));
        when(featureLoader.load(any())).thenThrow(new IllegalStateException("boom"));
        doThrow(new IllegalStateException("Query request not found"))
                .when(approvalPredictionPersistenceService).persist(any());

        assertThatCode(() -> service.predictForQuery(queryId)).doesNotThrowAnyException();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void predictForQueryFailsClosedOnANonFiniteProbability() {
        // A zero stddev slips past fromJson (unlike the trainer, which clamps it) and drives the
        // logit to infinity.
        var names = ApprovalFeatureVector.FEATURE_SCHEMA_V1;
        Map<String, Double> weights = new HashMap<>();
        Map<String, Double> means = new HashMap<>();
        Map<String, Double> stddevs = new HashMap<>();
        for (var name : names) {
            weights.put(name, 1.0);
            means.put(name, 0.0);
            stddevs.put(name, 0.0);
        }
        var degenerate = new TrainedApprovalModel("v1", names, 0.0, weights, means, stddevs);
        var entity = servingModel();
        entity.setCoefficients(degenerate.toJson(objectMapper));
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW)));
        when(modelRepository.findByOrganizationId(organizationId)).thenReturn(Optional.of(entity));
        when(featureLoader.load(any())).thenReturn(vector(false));

        service.predictForQuery(queryId);

        var command = capturePersisted();
        assertThat(command.failed()).isTrue();
        assertThat(command.probability()).isNull();
    }

    @Test
    void predictForQueryPersistsAFailedSentinelWhenTheCoefficientsAreCorrupt() {
        var corrupt = servingModel();
        corrupt.setCoefficients("{not json");
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW)));
        when(modelRepository.findByOrganizationId(organizationId)).thenReturn(Optional.of(corrupt));

        assertThatCode(() -> service.predictForQuery(queryId)).doesNotThrowAnyException();

        assertThat(capturePersisted().failed()).isTrue();
    }

    // ------------------------------------------------------- refreshForLateEstimate

    private static ApprovalPredictionSnapshot prediction(String featuresJson, boolean skipped,
                                                         boolean failed) {
        return new ApprovalPredictionSnapshot(UUID.randomUUID(), UUID.randomUUID(), 0.4,
                UUID.randomUUID(), 1, featuresJson, skipped, null, failed, null, Instant.now());
    }

    private static QueryEstimateSnapshot estimate(boolean supported, boolean failed) {
        return new QueryEstimateSnapshot(UUID.randomUUID(), UUID.randomUUID(), "postgresql",
                QueryType.SELECT, supported, 10L, null, "Seq Scan", 1.0, null, null, null, failed,
                null, null, Instant.now());
    }

    @Test
    void refreshForLateEstimateDoesNothingWhenDisabled() {
        service = build(false);

        service.refreshForLateEstimate(queryId);

        verifyNoInteractions(approvalPredictionLookupService, queryEstimateLookupService,
                approvalPredictionPersistenceService);
    }

    @Test
    void refreshForLateEstimateDoesNothingWithoutAPrediction() {
        when(approvalPredictionLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.empty());

        service.refreshForLateEstimate(queryId);

        verifyNoInteractions(queryEstimateLookupService, approvalPredictionPersistenceService);
    }

    @Test
    void refreshForLateEstimateDoesNothingForASkippedOrFailedRow() {
        when(approvalPredictionLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(prediction("{\"estimate_missing\":true}", true, false)));
        service.refreshForLateEstimate(queryId);

        when(approvalPredictionLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(prediction("{\"estimate_missing\":true}", false, true)));
        service.refreshForLateEstimate(queryId);

        verifyNoInteractions(queryEstimateLookupService, approvalPredictionPersistenceService);
    }

    @Test
    void refreshForLateEstimateDoesNothingWhenTheSnapshotAlreadyHadTheEstimate() {
        when(approvalPredictionLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(prediction("{\"estimate_missing\":false}", false, false)));

        service.refreshForLateEstimate(queryId);

        verifyNoInteractions(queryEstimateLookupService, approvalPredictionPersistenceService);
    }

    @Test
    void refreshForLateEstimateDoesNothingWhenTheSnapshotIsAbsentOrUnparseable() {
        when(approvalPredictionLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(prediction(null, false, false)));
        service.refreshForLateEstimate(queryId);

        when(approvalPredictionLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(prediction("{oops", false, false)));
        service.refreshForLateEstimate(queryId);

        verifyNoInteractions(queryEstimateLookupService, approvalPredictionPersistenceService);
    }

    @Test
    void refreshForLateEstimateDoesNothingWhenTheEstimateIsStillNotUsable() {
        when(approvalPredictionLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(prediction("{\"estimate_missing\":true}", false, false)));

        when(queryEstimateLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.empty());
        service.refreshForLateEstimate(queryId);

        // A transactional query is estimated unsupported but still publishes the completion event.
        when(queryEstimateLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(estimate(false, false)));
        service.refreshForLateEstimate(queryId);

        when(queryEstimateLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(estimate(true, true)));
        service.refreshForLateEstimate(queryId);

        verifyNoInteractions(approvalPredictionPersistenceService, modelRepository);
    }

    @Test
    void refreshForLateEstimateDoesNothingOnceTheQueryHasLeftReview() {
        when(approvalPredictionLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(prediction("{\"estimate_missing\":true}", false, false)));
        when(queryEstimateLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(estimate(true, false)));
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED)));

        service.refreshForLateEstimate(queryId);

        verifyNoInteractions(approvalPredictionPersistenceService, modelRepository);
    }

    @Test
    void refreshForLateEstimateDoesNothingWhenTheQueryIsGone() {
        when(approvalPredictionLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(prediction("{\"estimate_missing\":true}", false, false)));
        when(queryEstimateLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(estimate(true, false)));
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        service.refreshForLateEstimate(queryId);

        verifyNoInteractions(approvalPredictionPersistenceService, modelRepository);
    }

    @Test
    void refreshForLateEstimateRescoresWhenEveryGuardPasses() {
        when(approvalPredictionLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(prediction("{\"estimate_missing\":true}", false, false)));
        when(queryEstimateLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(estimate(true, false)));
        stubServingHappyPath(false);

        service.refreshForLateEstimate(queryId);

        var command = capturePersisted();
        assertThat(command.probability()).isNotNull();
        assertThat(command.featuresJson()).contains("\"estimate_missing\":false");
        assertThat(capturePublished().queryRequestId()).isEqualTo(queryId);
    }

    // ------------------------------------------------------------------------ training

    private static OrganizationView organization(UUID id, boolean disabled) {
        return new OrganizationView(id, "org", "org-" + id, disabled, null, null, null,
                Instant.now(), Instant.now());
    }

    @Test
    void trainAllDelegatesOncePerEnabledOrganization() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(organizationAdminService.list(any())).thenReturn(new PageResponse<>(
                List.of(organization(first, false), organization(second, false)), 0, 200, 2, 1));

        service.trainAll();

        verify(trainingService).trainForOrganization(first);
        verify(trainingService).trainForOrganization(second);
    }

    @Test
    void trainAllPagesThroughEveryOrganization() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(organizationAdminService.list(any()))
                .thenReturn(new PageResponse<>(List.of(organization(first, false)), 0, 200, 2, 2))
                .thenReturn(new PageResponse<>(List.of(organization(second, false)), 1, 200, 2, 2));

        service.trainAll();

        verify(trainingService).trainForOrganization(first);
        verify(trainingService).trainForOrganization(second);
    }

    @Test
    void trainAllSkipsDisabledOrganizations() {
        var disabled = UUID.randomUUID();
        when(organizationAdminService.list(any())).thenReturn(new PageResponse<>(
                List.of(organization(disabled, true)), 0, 200, 1, 1));

        service.trainAll();

        verify(trainingService, never()).trainForOrganization(disabled);
    }

    @Test
    void trainAllKeepsGoingAfterAnOrganizationFails() {
        var failing = UUID.randomUUID();
        var healthy = UUID.randomUUID();
        when(organizationAdminService.list(any())).thenReturn(new PageResponse<>(
                List.of(organization(failing, false), organization(healthy, false)), 0, 200, 2, 1));
        doThrow(new IllegalStateException("training blew up"))
                .when(trainingService).trainForOrganization(failing);

        assertThatCode(() -> service.trainAll()).doesNotThrowAnyException();

        verify(trainingService).trainForOrganization(healthy);
    }

    @Test
    void trainAllDoesNothingWhenDisabled() {
        service = build(false);

        service.trainAll();

        verifyNoInteractions(organizationAdminService, trainingService);
    }

    @Test
    void trainForOrganizationDelegatesToTheTrainingService() {
        service.trainForOrganization(organizationId);

        verify(trainingService).trainForOrganization(organizationId);
    }
}
