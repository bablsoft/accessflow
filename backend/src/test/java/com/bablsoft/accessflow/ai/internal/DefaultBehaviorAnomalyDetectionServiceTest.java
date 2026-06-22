package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.AnomalyDetectionProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorAnomalyEntity;
import com.bablsoft.accessflow.audit.api.BehaviorAuditAggregationService;
import com.bablsoft.accessflow.audit.api.BehaviorAuditSample;
import com.bablsoft.accessflow.audit.api.BehaviorSubjectRef;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.events.AnomalyDetectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultBehaviorAnomalyDetectionServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DS = UUID.randomUUID();
    // 12:34:56 floors to a 13:00? No: lookback PT1H floors the *end* to the hour → 12:00; window [11:00,12:00).
    private static final Instant NOW = Instant.parse("2026-01-01T12:34:56Z");
    private static final Instant WINDOW_START = Instant.parse("2026-01-01T11:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-01-01T12:00:00Z");

    private final BehaviorAuditAggregationService auditAggregationService =
            mock(BehaviorAuditAggregationService.class);
    private final BehaviorFeatureExtractor featureExtractor = mock(BehaviorFeatureExtractor.class);
    private final DefaultBehaviorBaselineService baselineService =
            mock(DefaultBehaviorBaselineService.class);
    private final StatisticalAnomalyDetector detector = mock(StatisticalAnomalyDetector.class);
    private final DefaultBehaviorAnomalyService anomalyService =
            mock(DefaultBehaviorAnomalyService.class);
    private final AnomalySummaryService summaryService = mock(AnomalySummaryService.class);
    private final UserQueryService userQueryService = mock(UserQueryService.class);
    private final DatasourceLookupService datasourceLookupService =
            mock(DatasourceLookupService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    // Real properties → defaults (lookback PT1H, maxBaselineSamples 90).
    private final AnomalyDetectionProperties properties =
            new AnomalyDetectionProperties(null, 0, 0, 0, 0, -1, null);

    private final DefaultBehaviorAnomalyDetectionService service =
            new DefaultBehaviorAnomalyDetectionService(auditAggregationService, featureExtractor,
                    baselineService, detector, anomalyService, summaryService, userQueryService,
                    datasourceLookupService, eventPublisher, properties);

    private BaselineState state;
    private WindowFeatures features;

    @BeforeEach
    void setUp() {
        var entity = new com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorBaselineEntity();
        entity.setId(UUID.randomUUID());
        state = new BaselineState(entity, BaselineProfile.empty());
        features = new WindowFeatures(WINDOW_START, WINDOW_END, 100, new int[24], 2,
                Set.of("orders"), Map.of("SELECT", 100), 5.0, 0.0, Set.of(11));
        when(userQueryService.findById(any())).thenReturn(Optional.empty());
        when(datasourceLookupService.findRef(any()))
                .thenReturn(Optional.of(new DatasourceRef(DS, "Prod DB")));
    }

    private static DetectedAnomaly anomaly() {
        return new DetectedAnomaly("query_count", 4.2, 100.0, 10.0, 2.0, Map.of("method", "zscore"));
    }

    private static BehaviorAuditSample sample() {
        return new BehaviorAuditSample(WINDOW_START.plusSeconds(60), true, "SELECT",
                List.of("orders"), 5L);
    }

    @Test
    void refreshAndDetectForReturnsZeroWhenWindowAlreadyFolded() {
        when(baselineService.load(ORG, USER, DS)).thenReturn(state);
        when(baselineService.alreadyFolded(state, WINDOW_START)).thenReturn(true);

        int count = service.refreshAndDetectFor(ORG, USER, DS, NOW);

        assertThat(count).isZero();
        verify(auditAggregationService, never()).samplesFor(any(), any(), any(), any(), any());
        verify(baselineService, never()).fold(any(), any(), any(), anyInt());
    }

    @Test
    void refreshAndDetectForReturnsZeroWhenNoSamples() {
        when(baselineService.load(ORG, USER, DS)).thenReturn(state);
        when(baselineService.alreadyFolded(state, WINDOW_START)).thenReturn(false);
        when(auditAggregationService.samplesFor(ORG, USER, DS, WINDOW_START, WINDOW_END))
                .thenReturn(List.of());

        int count = service.refreshAndDetectFor(ORG, USER, DS, NOW);

        assertThat(count).isZero();
        verify(detector, never()).detect(any(), any(), any());
        verify(baselineService, never()).fold(any(), any(), any(), anyInt());
    }

    @Test
    void refreshAndDetectForFoldsWindowEvenWhenNoAnomalies() {
        primeWindow(List.of());

        int count = service.refreshAndDetectFor(ORG, USER, DS, NOW);

        assertThat(count).isZero();
        verify(baselineService).fold(state, features, WINDOW_START, 90);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void refreshAndDetectForPersistsAndPublishesNewAnomaly() {
        var saved = savedEntity();
        primeWindow(List.of(anomaly()));
        when(anomalyService.existsForWindow(ORG, USER, DS, "query_count", WINDOW_START))
                .thenReturn(false);
        when(summaryService.summarize(eq(ORG), any(), any(), any()))
                .thenReturn(Optional.of("explanation"));
        when(anomalyService.persist(eq(ORG), eq(USER), eq(DS), any(), eq(WINDOW_START),
                eq(WINDOW_END), eq("explanation"))).thenReturn(Optional.of(saved));

        int count = service.refreshAndDetectFor(ORG, USER, DS, NOW);

        assertThat(count).isEqualTo(1);
        var event = ArgumentCaptor.forClass(AnomalyDetectedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().anomalyId()).isEqualTo(saved.getId());
        assertThat(event.getValue().organizationId()).isEqualTo(ORG);
        assertThat(event.getValue().userId()).isEqualTo(USER);
        assertThat(event.getValue().datasourceId()).isEqualTo(DS);
        assertThat(event.getValue().feature()).isEqualTo("query_count");
        assertThat(event.getValue().score()).isEqualTo(4.2);
        verify(baselineService).fold(state, features, WINDOW_START, 90);
    }

    @Test
    void refreshAndDetectForSkipsAnomalyThatAlreadyExistsForWindow() {
        primeWindow(List.of(anomaly()));
        when(anomalyService.existsForWindow(ORG, USER, DS, "query_count", WINDOW_START))
                .thenReturn(true);

        int count = service.refreshAndDetectFor(ORG, USER, DS, NOW);

        assertThat(count).isZero();
        verify(summaryService, never()).summarize(any(), any(), any(), any());
        verify(anomalyService, never()).persist(any(), any(), any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(baselineService).fold(state, features, WINDOW_START, 90);
    }

    @Test
    void refreshAndDetectForDoesNotPublishWhenPersistLosesRace() {
        primeWindow(List.of(anomaly()));
        when(anomalyService.existsForWindow(any(), any(), any(), any(), any())).thenReturn(false);
        when(summaryService.summarize(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(anomalyService.persist(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        int count = service.refreshAndDetectFor(ORG, USER, DS, NOW);

        assertThat(count).isZero();
        verify(eventPublisher, never()).publishEvent(any());
        verify(baselineService).fold(state, features, WINDOW_START, 90);
    }

    @Test
    void refreshAndDetectAllSumsCountsAcrossSubjects() {
        var subjectA = new BehaviorSubjectRef(ORG, USER, DS);
        var otherUser = UUID.randomUUID();
        var subjectB = new BehaviorSubjectRef(ORG, otherUser, DS);
        when(auditAggregationService.findActiveSubjects(WINDOW_START, WINDOW_END))
                .thenReturn(List.of(subjectA, subjectB));

        // Both subjects produce one persisted anomaly each.
        primeSubject(USER);
        primeSubject(otherUser);

        int count = service.refreshAndDetectAll(NOW);

        assertThat(count).isEqualTo(2);
        verify(baselineService, times(2)).fold(any(), any(), eq(WINDOW_START), eq(90));
    }

    @Test
    void refreshAndDetectAllSwallowsPerSubjectFailure() {
        var subjectA = new BehaviorSubjectRef(ORG, USER, DS);
        var otherUser = UUID.randomUUID();
        var subjectB = new BehaviorSubjectRef(ORG, otherUser, DS);
        when(auditAggregationService.findActiveSubjects(WINDOW_START, WINDOW_END))
                .thenReturn(List.of(subjectA, subjectB));

        // Subject A throws on load; subject B succeeds with one anomaly.
        when(baselineService.load(ORG, USER, DS)).thenThrow(new IllegalStateException("boom"));
        primeSubject(otherUser);

        int count = service.refreshAndDetectAll(NOW);

        // A failed and was swallowed; B contributed 1.
        assertThat(count).isEqualTo(1);
    }

    @Test
    void refreshAndDetectAllReturnsZeroForNoSubjects() {
        when(auditAggregationService.findActiveSubjects(WINDOW_START, WINDOW_END))
                .thenReturn(List.of());
        assertThat(service.refreshAndDetectAll(NOW)).isZero();
    }

    @Test
    void windowMathFloorsEndToLookbackBoundary() {
        when(auditAggregationService.findActiveSubjects(any(), any())).thenReturn(List.of());

        service.refreshAndDetectAll(NOW);

        // NOW=12:34:56; lookback PT1H → end floored to 12:00, start 11:00.
        verify(auditAggregationService).findActiveSubjects(WINDOW_START, WINDOW_END);
    }

    // ----- helpers -----

    private void primeWindow(List<DetectedAnomaly> detected) {
        when(baselineService.load(ORG, USER, DS)).thenReturn(state);
        when(baselineService.alreadyFolded(state, WINDOW_START)).thenReturn(false);
        when(auditAggregationService.samplesFor(ORG, USER, DS, WINDOW_START, WINDOW_END))
                .thenReturn(List.of(sample()));
        when(featureExtractor.extract(any(), eq(WINDOW_START), eq(WINDOW_END))).thenReturn(features);
        when(detector.detect(eq(state.profile()), eq(features), eq(properties))).thenReturn(detected);
    }

    /** Wire one subject (user) to detect, persist, and publish exactly one anomaly. */
    private void primeSubject(UUID user) {
        var subjectState = new BaselineState(
                new com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorBaselineEntity(),
                BaselineProfile.empty());
        when(baselineService.load(ORG, user, DS)).thenReturn(subjectState);
        when(baselineService.alreadyFolded(subjectState, WINDOW_START)).thenReturn(false);
        when(auditAggregationService.samplesFor(ORG, user, DS, WINDOW_START, WINDOW_END))
                .thenReturn(List.of(sample()));
        when(featureExtractor.extract(any(), eq(WINDOW_START), eq(WINDOW_END))).thenReturn(features);
        when(detector.detect(any(), eq(features), eq(properties))).thenReturn(List.of(anomaly()));
        when(anomalyService.existsForWindow(eq(ORG), eq(user), eq(DS), eq("query_count"),
                eq(WINDOW_START))).thenReturn(false);
        when(summaryService.summarize(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(anomalyService.persist(eq(ORG), eq(user), eq(DS), any(), eq(WINDOW_START),
                eq(WINDOW_END), any())).thenReturn(Optional.of(savedEntity()));
    }

    private BehaviorAnomalyEntity savedEntity() {
        var e = new BehaviorAnomalyEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(ORG);
        e.setUserId(USER);
        e.setDatasourceId(DS);
        e.setFeature("query_count");
        e.setScore(4.2);
        return e;
    }
}
