package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.EffectiveDeploymentPermission;
import com.bablsoft.accessflow.deploygov.api.InvalidDeploymentMetricsPeriodException;
import com.bablsoft.accessflow.deploygov.api.LeadTimeSource;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentMetricsRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentMetricsRepository.ApprovalStats;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentMetricsRepository.DailyStats;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentMetricsRepository.ExecutionStats;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentMetricsRepository.RestoreStats;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentMetricsRepository.ReviewStats;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentMetricsScope;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultDeploymentMetricsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-04T00:00:00Z");
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID CALLER_ID = UUID.randomUUID();

    private DeploymentMetricsRepository metricsRepository;
    private DeploymentEnvironmentRepository environmentRepository;
    private DeploymentPipelineRepository pipelineRepository;
    private EffectiveDeploymentPermissionResolver permissionResolver;
    private DefaultDeploymentMetricsService service;
    private DeploymentPipelineEntity pipeline;

    @BeforeEach
    void setUp() {
        metricsRepository = mock(DeploymentMetricsRepository.class);
        environmentRepository = mock(DeploymentEnvironmentRepository.class);
        pipelineRepository = mock(DeploymentPipelineRepository.class);
        permissionResolver = mock(EffectiveDeploymentPermissionResolver.class);
        service = new DefaultDeploymentMetricsService(metricsRepository, environmentRepository,
                new DeploymentPipelineVisibility(pipelineRepository, permissionResolver),
                Clock.fixed(NOW, ZoneOffset.UTC));
        pipeline = new DeploymentPipelineEntity();
        pipeline.setId(UUID.randomUUID());
        pipeline.setOrganizationId(ORG_ID);
        when(pipelineRepository.findByIdAndOrganizationId(pipeline.getId(), ORG_ID))
                .thenReturn(Optional.of(pipeline));
        when(permissionResolver.resolve(any(), any())).thenReturn(Optional.empty());
        stubRepository(new ExecutionStats(0, 0, 0, 0, 0, null, null), List.of());
    }

    // ------------------------------------------------------------------ period

    @Test
    void noBoundsDefaultToTheTrailingThirtyDays() {
        var view = service.organizationMetrics(ORG_ID, null);

        assertThat(view.period().to()).isEqualTo(NOW);
        assertThat(view.period().from()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        assertThat(capturedScope().to()).isEqualTo(NOW);
    }

    @Test
    void aLoneFromRunsThirtyDaysForward() {
        var view = service.organizationMetrics(ORG_ID, filter(FROM, null, null, null));

        assertThat(view.period().from()).isEqualTo(FROM);
        assertThat(view.period().to()).isEqualTo(FROM.plus(Duration.ofDays(30)));
    }

    @Test
    void aLoneToRunsThirtyDaysBack() {
        var view = service.organizationMetrics(ORG_ID, filter(null, TO, null, null));

        assertThat(view.period().from()).isEqualTo(TO.minus(Duration.ofDays(30)));
        assertThat(view.period().to()).isEqualTo(TO);
    }

    @Test
    void exactly366DaysIsAllowed() {
        var view = service.organizationMetrics(ORG_ID,
                filter(FROM, FROM.plus(Duration.ofDays(366)), null, null));

        assertThat(view.daily()).hasSize(366);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-04T00:00:00Z,2026-10-04T00:00:00Z",
            "2026-10-05T00:00:00Z,2026-10-04T00:00:00Z",
            "2025-01-01T00:00:00Z,2026-01-02T00:00:01Z",
    })
    void anEmptyInvertedOrOversizedWindowIsRejected(Instant from, Instant to) {
        assertThatThrownBy(() -> service.organizationMetrics(ORG_ID, filter(from, to, null, null)))
                .isInstanceOf(InvalidDeploymentMetricsPeriodException.class);
        assertThatThrownBy(() -> service.pipelineMetrics(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.QUERY_ADMIN), filter(from, to, null, null)))
                .isInstanceOf(InvalidDeploymentMetricsPeriodException.class);
        verifyNoInteractions(metricsRepository);
    }

    // -------------------------------------------------------------- visibility

    @ParameterizedTest
    @EnumSource(value = Permission.class,
            names = {"DEPLOYMENT_PIPELINE_MANAGE", "DEPLOYMENT_REVIEW", "QUERY_ADMIN"})
    void eachFunctionalPermissionReadsPipelineMetrics(Permission permission) {
        var view = service.pipelineMetrics(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(permission), filter(FROM, TO, null, null));

        assertThat(view.period().from()).isEqualTo(FROM);
        var scope = capturedScope();
        assertThat(scope.pipelineId()).isEqualTo(pipeline.getId());
        assertThat(scope.organizationId()).isEqualTo(ORG_ID);
        assertThat(scope.environmentIds()).isNull();
    }

    @Test
    void anEffectiveTriggerGrantReadsPipelineMetrics() {
        when(permissionResolver.resolve(pipeline.getId(), CALLER_ID)).thenReturn(Optional.of(
                new EffectiveDeploymentPermission(pipeline.getId(), CALLER_ID, true, false, null)));

        service.pipelineMetrics(pipeline.getId(), ORG_ID, CALLER_ID, Set.of(),
                filter(FROM, TO, null, null));

        verify(metricsRepository).executionStats(any());
    }

    @Test
    void neitherAPermissionNorAGrantReadsAsNotFound() {
        assertThatThrownBy(() -> service.pipelineMetrics(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.QUERY_SUBMIT_SELECT), filter(FROM, TO, null, null)))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
        verifyNoInteractions(metricsRepository);
    }

    @Test
    void aPipelineInAnotherOrganizationReadsAsNotFound() {
        var otherOrg = UUID.randomUUID();
        when(pipelineRepository.findByIdAndOrganizationId(pipeline.getId(), otherOrg))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pipelineMetrics(pipeline.getId(), otherOrg, CALLER_ID,
                Set.of(Permission.QUERY_ADMIN), filter(FROM, TO, null, null)))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void thePipelineReadIgnoresTheFilterPipelineId() {
        service.pipelineMetrics(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.QUERY_ADMIN), filter(FROM, TO, null, UUID.randomUUID()));

        assertThat(capturedScope().pipelineId()).isEqualTo(pipeline.getId());
    }

    // ------------------------------------------------------------- environment

    @Test
    void aPipelineEnvironmentNameResolvesToItsId() {
        var environment = new DeploymentEnvironmentEntity();
        environment.setId(UUID.randomUUID());
        when(environmentRepository.findByPipelineIdAndNameIgnoreCase(pipeline.getId(), "PROD"))
                .thenReturn(Optional.of(environment));

        service.pipelineMetrics(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.QUERY_ADMIN), filter(FROM, TO, " PROD ", null));

        assertThat(capturedScope().environmentIds()).containsExactly(environment.getId());
    }

    @Test
    void anUnknownPipelineEnvironmentYieldsEmptyMetricsWithoutQuerying() {
        when(environmentRepository.findByPipelineIdAndNameIgnoreCase(pipeline.getId(), "nope"))
                .thenReturn(Optional.empty());

        var view = service.pipelineMetrics(pipeline.getId(), ORG_ID, CALLER_ID,
                Set.of(Permission.QUERY_ADMIN), filter(FROM, TO, "nope", null));

        assertEmpty(view);
        verifyNoInteractions(metricsRepository);
    }

    @Test
    void anOrganizationEnvironmentNameMatchesEveryPipeline() {
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(environmentRepository.findIdsByOrganizationIdAndNameIgnoreCase(ORG_ID, "prod"))
                .thenReturn(ids);
        var pipelineId = UUID.randomUUID();

        service.organizationMetrics(ORG_ID, filter(FROM, TO, "prod", pipelineId));

        var scope = capturedScope();
        assertThat(scope.environmentIds()).isEqualTo(ids);
        assertThat(scope.pipelineId()).isEqualTo(pipelineId);
    }

    @Test
    void anUnknownOrganizationEnvironmentYieldsEmptyMetrics() {
        when(environmentRepository.findIdsByOrganizationIdAndNameIgnoreCase(ORG_ID, "nope"))
                .thenReturn(List.of());

        assertEmpty(service.organizationMetrics(ORG_ID, filter(FROM, TO, "nope", null)));
        verifyNoInteractions(metricsRepository);
    }

    @Test
    void aBlankEnvironmentIsNoFilter() {
        service.organizationMetrics(ORG_ID, filter(FROM, TO, "  ", null));

        assertThat(capturedScope().environmentIds()).isNull();
        verifyNoInteractions(environmentRepository);
    }

    // ----------------------------------------------------------------- shaping

    @Test
    void aggregatesAreShapedIntoEveryBlock() {
        stubRepository(new ExecutionStats(6, 1, 2, 1, 6, 3600.4, 7200.6),
                List.of(new DailyStats("2026-10-02", 6, 1, 2)));
        when(metricsRepository.restoreStats(any())).thenReturn(new RestoreStats(2, 1, 600.0, 900.0));
        when(metricsRepository.approvalStats(any())).thenReturn(new ApprovalStats(3, 120.0, 240.0));
        when(metricsRepository.reviewStats(any())).thenReturn(new ReviewStats(1, 4, 2));

        var view = service.organizationMetrics(ORG_ID, filter(FROM, TO, null, null));

        assertThat(view.deploymentFrequency().total()).isEqualTo(6);
        assertThat(view.deploymentFrequency().perDay()).isEqualTo(2.0);
        assertThat(view.leadTime().p50Seconds()).isEqualTo(3600L);
        assertThat(view.leadTime().p95Seconds()).isEqualTo(7201L);
        assertThat(view.leadTime().sampleSize()).isEqualTo(6);
        assertThat(view.leadTime().source()).isEqualTo(LeadTimeSource.COMMIT_TIMESTAMP);
        assertThat(view.changeFailureRate().rate()).isEqualTo(0.5);
        assertThat(view.changeFailureRate().unreported()).isEqualTo(1);
        assertThat(view.timeToRestore().p50Seconds()).isEqualTo(600L);
        assertThat(view.timeToRestore().restored()).isEqualTo(2);
        assertThat(view.timeToRestore().openIncidents()).isEqualTo(1);
        assertThat(view.approvalLatency().p95Seconds()).isEqualTo(240L);
        assertThat(view.approvalLatency().sampleSize()).isEqualTo(3);
        assertThat(view.timeoutRate().rate()).isEqualTo(0.25);
        assertThat(view.breakGlassCount()).isEqualTo(2);
        assertThat(view.daily()).extracting(DeploymentMetricsView.DailyBucket::date)
                .containsExactly(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"),
                        LocalDate.parse("2026-10-03"));
        assertThat(view.daily().get(0).deployments()).isZero();
        assertThat(view.daily().get(1).deployments()).isEqualTo(6);
        assertThat(view.daily().get(1).rolledBack()).isEqualTo(2);
        assertThat(view.daily().get(2).deployments()).isZero();
    }

    @Test
    void emptyDenominatorsYieldNullRatesAndPercentiles() {
        var view = service.organizationMetrics(ORG_ID, filter(FROM, TO, null, null));

        assertEmpty(view);
    }

    @Test
    void aPartialDayWindowTouchesBothUtcDays() {
        var view = service.organizationMetrics(ORG_ID, filter(
                Instant.parse("2026-10-01T22:00:00Z"), Instant.parse("2026-10-02T02:00:00Z"),
                null, null));

        assertThat(view.daily()).extracting(DeploymentMetricsView.DailyBucket::date)
                .containsExactly(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"));
        assertThat(view.deploymentFrequency().perDay()).isZero();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "0,0,null",
            "4,4,COMMIT_TIMESTAMP",
            "4,0,SUBMITTED_AT",
            "4,1,MIXED",
    })
    void leadTimeSourceFollowsTheCommitTimestampCoverage(long executed, long commitSourced,
                                                         LeadTimeSource expected) {
        assertThat(DefaultDeploymentMetricsService.leadTimeSource(
                new ExecutionStats(executed, 0, 0, 0, commitSourced, null, null)))
                .isEqualTo(expected);
    }

    // ----------------------------------------------------------------- helpers

    private void stubRepository(ExecutionStats execution, List<DailyStats> daily) {
        when(metricsRepository.executionStats(any())).thenReturn(execution);
        when(metricsRepository.restoreStats(any())).thenReturn(new RestoreStats(0, 0, null, null));
        when(metricsRepository.approvalStats(any())).thenReturn(new ApprovalStats(0, null, null));
        when(metricsRepository.reviewStats(any())).thenReturn(new ReviewStats(0, 0, 0));
        when(metricsRepository.daily(any())).thenReturn(daily);
    }

    private DeploymentMetricsScope capturedScope() {
        var captor = ArgumentCaptor.forClass(DeploymentMetricsScope.class);
        verify(metricsRepository).executionStats(captor.capture());
        return captor.getValue();
    }

    private static DeploymentMetricsFilter filter(Instant from, Instant to, String environment,
                                                  UUID pipelineId) {
        return new DeploymentMetricsFilter(from, to, environment, pipelineId);
    }

    private static void assertEmpty(DeploymentMetricsView view) {
        assertThat(view.deploymentFrequency().total()).isZero();
        assertThat(view.deploymentFrequency().perDay()).isZero();
        assertThat(view.leadTime().p50Seconds()).isNull();
        assertThat(view.leadTime().p95Seconds()).isNull();
        assertThat(view.leadTime().source()).isNull();
        assertThat(view.changeFailureRate().rate()).isNull();
        assertThat(view.timeToRestore().p50Seconds()).isNull();
        assertThat(view.approvalLatency().p50Seconds()).isNull();
        assertThat(view.timeoutRate().rate()).isNull();
        assertThat(view.breakGlassCount()).isZero();
        assertThat(view.daily()).isNotEmpty()
                .allSatisfy(day -> assertThat(day.deployments()).isZero());
    }
}
