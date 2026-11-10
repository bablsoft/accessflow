package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.compliance.api.Approver;
import com.bablsoft.accessflow.compliance.api.ComplianceReportRequest;
import com.bablsoft.accessflow.compliance.api.ComplianceReportType;
import com.bablsoft.accessflow.compliance.api.InvalidReportPeriodException;
import com.bablsoft.accessflow.compliance.internal.config.ComplianceProperties;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationAdminService;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.OrganizationDataClassificationView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsService;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.deploygov.api.InvalidDeploymentMetricsPeriodException;
import com.bablsoft.accessflow.deploygov.api.LeadTimeSource;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultComplianceReportServiceTest {

    @Mock QuerySnapshotService snapshotService;
    @Mock DataClassificationAdminService dataClassificationAdminService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock UserAdminService userAdminService;
    @Mock ReviewDecisionsParser reviewDecisionsParser;
    @Mock com.bablsoft.accessflow.lifecycle.api.LifecycleRunLookupService lifecycleRunLookupService;
    @Mock DeploymentPipelineAdminService pipelineAdminService;
    @Mock DeploymentEnvironmentLookupService environmentLookupService;
    @Mock DeploymentMetricsService deploymentMetricsService;

    private DefaultComplianceReportService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID dsId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant from = Instant.parse("2026-04-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-07-01T00:00:00Z");
    private final Instant generatedAt = Instant.parse("2026-07-02T09:00:00Z");

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(generatedAt, ZoneOffset.UTC);
        var properties = new ComplianceProperties(Duration.ofDays(366), 2, 0, 0);
        service = new DefaultComplianceReportService(snapshotService, dataClassificationAdminService,
                datasourceAdminService, userAdminService, reviewDecisionsParser,
                lifecycleRunLookupService, pipelineAdminService, environmentLookupService,
                deploymentMetricsService, properties,
                clock);
        lenient().when(datasourceAdminService.listForAdmin(eq(orgId), any()))
                .thenReturn(PageResponse.empty(0, 1000));
        lenient().when(userAdminService.findByIds(eq(orgId), any()))
                .thenReturn(Map.of(userId, user()));
    }

    private UserView user() {
        return new UserView(userId, "a@x.com", "Alice", com.bablsoft.accessflow.core.api.UserRoleType.ANALYST,
                orgId, true, com.bablsoft.accessflow.core.api.AuthProviderType.LOCAL, null, null, null,
                false, generatedAt);
    }

    private QuerySnapshotView snapshot(QueryType type, List<String> tables) {
        return new QuerySnapshotView(UUID.randomUUID(), UUID.randomUUID(), orgId, dsId, userId,
                "SQL", type, false, DbType.POSTGRESQL, tables, null, null, "[]", 1L, 2, from, from,
                "SQL WHERE tenant = ?");
    }

    private OrganizationDataClassificationView tag(String table, DataClassification classification) {
        return new OrganizationDataClassificationView(UUID.randomUUID(), dsId, "Prod", table, null,
                classification, null, from, from);
    }

    @Test
    void classifiedAccessJoinsSnapshotsToClassifications() {
        when(snapshotService.findForPeriod(eq(orgId), eq(from), eq(to), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(snapshot(QueryType.SELECT, List.of("customers"))));
        when(dataClassificationAdminService.listForOrganization(orgId))
                .thenReturn(List.of(tag("customers", DataClassification.PII)));

        var report = service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, from, to, null));

        assertThat(report.type()).isEqualTo(ComplianceReportType.CLASSIFIED_ACCESS);
        assertThat(report.generatedAt()).isEqualTo(generatedAt);
        assertThat(report.classifiedAccess()).hasSize(1);
        assertThat(report.classifiedAccess().getFirst().submitterEmail()).isEqualTo("a@x.com");
        assertThat(report.auditTrail()).isEmpty();
        assertThat(report.truncated()).isFalse();
    }

    @Test
    void classifiedAccessFlagsTruncationAndTrimsToCap() {
        // cap = 2; return cap+1 = 3 to trigger truncation.
        when(snapshotService.findForPeriod(eq(orgId), eq(from), eq(to), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(
                        snapshot(QueryType.SELECT, List.of("customers")),
                        snapshot(QueryType.SELECT, List.of("customers")),
                        snapshot(QueryType.SELECT, List.of("customers"))));
        when(dataClassificationAdminService.listForOrganization(orgId))
                .thenReturn(List.of(tag("customers", DataClassification.PII)));

        var report = service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, from, to, null));

        assertThat(report.truncated()).isTrue();
        assertThat(report.classifiedAccess()).hasSize(2);
    }

    @Test
    void regulatoryTrailFiltersToDdlAndDeleteAndUsesApprovers() {
        when(snapshotService.findForPeriod(eq(orgId), eq(from), eq(to), isNull(),
                eq(Set.of(QueryType.DDL, QueryType.DELETE)), eq(3)))
                .thenReturn(List.of(snapshot(QueryType.DELETE, List.of("customers"))));
        when(reviewDecisionsParser.approvers(any()))
                .thenReturn(List.of(new Approver("rev@x.com", "Rev", "APPROVED", from)));

        var report = service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.REGULATORY_AUDIT_TRAIL, from, to, null));

        assertThat(report.type()).isEqualTo(ComplianceReportType.REGULATORY_AUDIT_TRAIL);
        assertThat(report.auditTrail()).hasSize(1);
        assertThat(report.auditTrail().getFirst().approvers()).hasSize(1);
        assertThat(report.auditTrail().getFirst().effectiveSql()).isEqualTo("SQL WHERE tenant = ?");
        assertThat(report.auditTrail().getFirst().approvers().getFirst().email()).isEqualTo("rev@x.com");
        assertThat(report.classifiedAccess()).isEmpty();
    }

    @Test
    void retentionAdherenceMapsLifecycleRuns() {
        var run = new com.bablsoft.accessflow.lifecycle.api.LifecycleRunView(
                UUID.randomUUID(), orgId, dsId,
                com.bablsoft.accessflow.lifecycle.api.LifecycleRunKind.RETENTION_POLICY,
                UUID.randomUUID(), null,
                com.bablsoft.accessflow.lifecycle.api.LifecycleRunStatus.COMPLETED,
                com.bablsoft.accessflow.lifecycle.api.LifecycleAction.SOFT_DELETE,
                42L, "SOFT_DELETE(deleted_at)", from, to, from);
        when(lifecycleRunLookupService.findForPeriod(eq(orgId), eq(from), eq(to), isNull(), eq(3)))
                .thenReturn(List.of(run));

        var report = service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.RETENTION_ADHERENCE, from, to, null));

        assertThat(report.type()).isEqualTo(ComplianceReportType.RETENTION_ADHERENCE);
        assertThat(report.retentionAdherence()).hasSize(1);
        assertThat(report.retentionAdherence().getFirst().affectedRows()).isEqualTo(42L);
        assertThat(report.retentionAdherence().getFirst().action()).isEqualTo("SOFT_DELETE");
        assertThat(report.retentionAdherence().getFirst().method()).isEqualTo("SOFT_DELETE(deleted_at)");
        assertThat(report.auditTrail()).isEmpty();
        assertThat(report.classifiedAccess()).isEmpty();
    }

    @Test
    void scopesToDatasourceWhenRequested() {
        when(snapshotService.findForPeriod(eq(orgId), eq(from), eq(to), eq(dsId), isNull(), eq(3)))
                .thenReturn(List.of());
        when(dataClassificationAdminService.listForOrganization(orgId)).thenReturn(List.of());

        var report = service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, from, to, dsId));

        assertThat(report.datasourceId()).isEqualTo(dsId);
        verify(snapshotService).findForPeriod(eq(orgId), eq(from), eq(to), eq(dsId), isNull(), eq(3));
    }

    @Test
    void rejectsInvertedPeriod() {
        assertThatThrownBy(() -> service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, to, from, null)))
                .isInstanceOf(InvalidReportPeriodException.class);
    }

    @Test
    void rejectsMissingPeriodBound() {
        assertThatThrownBy(() -> service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, null, to, null)))
                .isInstanceOf(InvalidReportPeriodException.class);
    }

    @Test
    void rejectsPeriodLongerThanMax() {
        var tooLong = from.plus(Duration.ofDays(400));
        assertThatThrownBy(() -> service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, from, tooLong, null)))
                .isInstanceOf(InvalidReportPeriodException.class);
    }

    private DeploymentPipelineView pipeline(String name) {
        return new DeploymentPipelineView(UUID.randomUUID(), orgId, name,
                PipelineProvider.GITHUB_ACTIONS, null, null, null, false, null, true, from, from);
    }

    private DeploymentEnvironmentView environment(DeploymentPipelineView pipeline, String name,
                                                  int sortOrder) {
        return new DeploymentEnvironmentView(UUID.randomUUID(), pipeline.id(), name, sortOrder, true,
                null, null, false, from, List.of(), null, false, null);
    }

    private DeploymentMetricsView metrics(long deployments) {
        return new DeploymentMetricsView(
                new DeploymentMetricsView.Period(from, to),
                new DeploymentMetricsView.DeploymentFrequency(deployments, 0.5),
                new DeploymentMetricsView.LeadTime(600L, 3600L, deployments, LeadTimeSource.MIXED),
                new DeploymentMetricsView.ChangeFailureRate(0.125, 1, 0, 2, deployments),
                new DeploymentMetricsView.TimeToRestore(120L, 900L, 1, 1),
                new DeploymentMetricsView.ApprovalLatency(60L, 300L, 3),
                new DeploymentMetricsView.TimeoutRate(0.25, 1, 4),
                2,
                List.of());
    }

    private DeploymentMetricsView emptyMetrics() {
        return new DeploymentMetricsView(
                new DeploymentMetricsView.Period(from, to),
                new DeploymentMetricsView.DeploymentFrequency(0, 0),
                new DeploymentMetricsView.LeadTime(null, null, 0, null),
                new DeploymentMetricsView.ChangeFailureRate(null, 0, 0, 0, 0),
                new DeploymentMetricsView.TimeToRestore(null, null, 0, 0),
                new DeploymentMetricsView.ApprovalLatency(null, null, 0),
                new DeploymentMetricsView.TimeoutRate(null, 0, 0),
                0,
                List.of());
    }

    private void stubPipelines(DeploymentPipelineView... pipelines) {
        when(pipelineAdminService.list(eq(orgId), any()))
                .thenReturn(new PageResponse<>(List.of(pipelines), 0, 100, pipelines.length, 1));
    }

    private ComplianceReportRequest deploymentRequest(UUID datasourceId) {
        return new ComplianceReportRequest(ComplianceReportType.DEPLOYMENT_METRICS, from, to,
                datasourceId);
    }

    @Test
    void deploymentMetricsHasOneRowPerPipelineAndEnvironmentFromTheMetricsService() {
        var payments = pipeline("payments");
        var staging = environment(payments, "staging", 0);
        stubPipelines(payments);
        when(environmentLookupService.listByPipeline(payments.id()))
                .thenReturn(List.of(staging));
        when(deploymentMetricsService.organizationMetrics(eq(orgId), any()))
                .thenReturn(metrics(8));

        var report = service.generate(orgId, deploymentRequest(null));

        var filter = ArgumentCaptor.forClass(DeploymentMetricsFilter.class);
        verify(deploymentMetricsService).organizationMetrics(eq(orgId), filter.capture());
        assertThat(filter.getValue()).isEqualTo(
                new DeploymentMetricsFilter(from, to, null, payments.id(), staging.id()));
        assertThat(report.type()).isEqualTo(ComplianceReportType.DEPLOYMENT_METRICS);
        assertThat(report.generatedAt()).isEqualTo(generatedAt);
        assertThat(report.truncated()).isFalse();
        assertThat(report.rowCount()).isEqualTo(1);
        var row = report.deploymentMetrics().getFirst();
        assertThat(row.pipelineId()).isEqualTo(payments.id());
        assertThat(row.pipelineName()).isEqualTo("payments");
        assertThat(row.environmentId()).isEqualTo(staging.id());
        assertThat(row.environmentName()).isEqualTo("staging");
        assertThat(row.deployments()).isEqualTo(8);
        assertThat(row.leadTimeP50Seconds()).isEqualTo(600L);
        assertThat(row.leadTimeP95Seconds()).isEqualTo(3600L);
        assertThat(row.leadTimeSource()).isEqualTo("MIXED");
        assertThat(row.changeFailureRate()).isEqualTo(0.125);
        assertThat(row.failed()).isEqualTo(1);
        assertThat(row.rolledBack()).isZero();
        assertThat(row.unreported()).isEqualTo(2);
        assertThat(row.timeToRestoreP50Seconds()).isEqualTo(120L);
        assertThat(row.timeToRestoreP95Seconds()).isEqualTo(900L);
        assertThat(row.openIncidents()).isEqualTo(1);
        assertThat(row.approvalLatencyP50Seconds()).isEqualTo(60L);
        assertThat(row.approvalLatencyP95Seconds()).isEqualTo(300L);
        assertThat(row.timeoutRate()).isEqualTo(0.25);
        assertThat(row.timedOut()).isEqualTo(1);
        assertThat(row.breakGlassCount()).isEqualTo(2);
    }

    @Test
    void deploymentMetricsKeepsNullPercentilesAndSource() {
        var payments = pipeline("payments");
        stubPipelines(payments);
        when(environmentLookupService.listByPipeline(payments.id()))
                .thenReturn(List.of(environment(payments, "prod", 0)));
        when(deploymentMetricsService.organizationMetrics(eq(orgId), any()))
                .thenReturn(emptyMetrics());

        var row = service.generate(orgId, deploymentRequest(null)).deploymentMetrics().getFirst();

        assertThat(row.leadTimeP50Seconds()).isNull();
        assertThat(row.leadTimeSource()).isNull();
        assertThat(row.changeFailureRate()).isNull();
        assertThat(row.timeoutRate()).isNull();
    }

    @Test
    void deploymentMetricsOrdersPipelinesByNameAndEnvironmentsByLadder() {
        var zeta = pipeline("Zeta");
        var alpha = pipeline("alpha");
        stubPipelines(zeta, alpha);
        when(environmentLookupService.listByPipeline(alpha.id())).thenReturn(List.of());
        when(environmentLookupService.listByPipeline(zeta.id()))
                .thenReturn(List.of(environment(zeta, "dev", 0), environment(zeta, "prod", 1)));
        when(deploymentMetricsService.organizationMetrics(eq(orgId), any()))
                .thenReturn(metrics(1));

        var report = service.generate(orgId, deploymentRequest(null));

        assertThat(report.deploymentMetrics())
                .extracting(r -> r.pipelineName() + "/" + r.environmentName())
                .containsExactly("Zeta/dev", "Zeta/prod");
        var order = org.mockito.Mockito.inOrder(environmentLookupService);
        order.verify(environmentLookupService).listByPipeline(alpha.id());
        order.verify(environmentLookupService).listByPipeline(zeta.id());
    }

    @Test
    void deploymentMetricsPagesThroughEveryPipeline() {
        var first = pipeline("first");
        var second = pipeline("second");
        var pages = ArgumentCaptor.forClass(PageRequest.class);
        when(pipelineAdminService.list(eq(orgId), pages.capture()))
                .thenReturn(new PageResponse<>(List.of(first), 0, 100, 101, 2))
                .thenReturn(new PageResponse<>(List.of(second), 1, 100, 101, 2));
        when(environmentLookupService.listByPipeline(any())).thenReturn(List.of());

        var report = service.generate(orgId, deploymentRequest(null));

        assertThat(report.deploymentMetrics()).isEmpty();
        assertThat(pages.getAllValues()).extracting(PageRequest::page).containsExactly(0, 1);
        verify(environmentLookupService).listByPipeline(first.id());
        verify(environmentLookupService).listByPipeline(second.id());
    }

    @Test
    void deploymentMetricsStopsAtTheRowCapAndFlagsTruncation() {
        var one = pipeline("a");
        var two = pipeline("b");
        stubPipelines(one, two);
        when(environmentLookupService.listByPipeline(one.id()))
                .thenReturn(List.of(environment(one, "dev", 0), environment(one, "prod", 1)));
        when(environmentLookupService.listByPipeline(two.id()))
                .thenReturn(List.of(environment(two, "prod", 0)));
        when(deploymentMetricsService.organizationMetrics(eq(orgId), any()))
                .thenReturn(metrics(1));

        var report = service.generate(orgId, deploymentRequest(null));

        assertThat(report.truncated()).isTrue();
        assertThat(report.deploymentMetrics()).hasSize(2);
        verify(deploymentMetricsService, times(2)).organizationMetrics(eq(orgId), any());
    }

    @Test
    void deploymentMetricsAtExactlyTheCapIsNotTruncated() {
        var one = pipeline("a");
        stubPipelines(one);
        when(environmentLookupService.listByPipeline(one.id()))
                .thenReturn(List.of(environment(one, "dev", 0), environment(one, "prod", 1)));
        when(deploymentMetricsService.organizationMetrics(eq(orgId), any()))
                .thenReturn(metrics(1));

        var report = service.generate(orgId, deploymentRequest(null));

        assertThat(report.truncated()).isFalse();
        assertThat(report.deploymentMetrics()).hasSize(2);
    }

    @Test
    void deploymentMetricsIgnoresTheDatasourceScope() {
        stubPipelines();

        var report = service.generate(orgId, deploymentRequest(dsId));

        assertThat(report.datasourceId()).isNull();
        assertThat(report.deploymentMetrics()).isEmpty();
        verify(deploymentMetricsService, never()).organizationMetrics(any(), any());
    }

    @Test
    void deploymentMetricsRejectsAnEmptyWindow() {
        assertThatThrownBy(() -> service.generate(orgId,
                new ComplianceReportRequest(ComplianceReportType.DEPLOYMENT_METRICS, from, from, null)))
                .isInstanceOf(InvalidReportPeriodException.class);
        verify(pipelineAdminService, never()).list(any(), any());
    }

    @Test
    void deploymentMetricsTranslatesTheMetricsPeriodException() {
        var one = pipeline("a");
        stubPipelines(one);
        when(environmentLookupService.listByPipeline(one.id()))
                .thenReturn(List.of(environment(one, "prod", 0)));
        when(deploymentMetricsService.organizationMetrics(eq(orgId), any()))
                .thenThrow(new InvalidDeploymentMetricsPeriodException());

        assertThatThrownBy(() -> service.generate(orgId, deploymentRequest(null)))
                .isInstanceOf(InvalidReportPeriodException.class)
                .extracting(e -> ((InvalidReportPeriodException) e).messageKey())
                .isEqualTo("error.invalid_report_period");
    }

    @Test
    void deploymentMetricsSelectsEachEnvironmentByIdNotByName() {
        // Names are unique per pipeline only case-sensitively, so "Prod" and "prod" can coexist.
        var payments = pipeline("payments");
        var upper = environment(payments, "Prod", 0);
        var lower = environment(payments, "prod", 1);
        stubPipelines(payments);
        when(environmentLookupService.listByPipeline(payments.id())).thenReturn(List.of(upper, lower));
        when(deploymentMetricsService.organizationMetrics(eq(orgId), any()))
                .thenReturn(metrics(1));

        service.generate(orgId, deploymentRequest(null));

        var filter = ArgumentCaptor.forClass(DeploymentMetricsFilter.class);
        verify(deploymentMetricsService, times(2)).organizationMetrics(eq(orgId), filter.capture());
        assertThat(filter.getAllValues())
                .extracting(DeploymentMetricsFilter::environmentId, DeploymentMetricsFilter::environment)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(upper.id(), null),
                        org.assertj.core.groups.Tuple.tuple(lower.id(), null));
    }

    @Test
    void deploymentMetricsRejectsAWindowOverTheMetricsCapEvenWithoutPipelines() {
        var lenientProperties = new ComplianceProperties(Duration.ofDays(800), 2, 0, 0);
        var wide = new DefaultComplianceReportService(snapshotService, dataClassificationAdminService,
                datasourceAdminService, userAdminService, reviewDecisionsParser,
                lifecycleRunLookupService, pipelineAdminService, environmentLookupService,
                deploymentMetricsService, lenientProperties,
                Clock.fixed(generatedAt, ZoneOffset.UTC));

        assertThatThrownBy(() -> wide.generate(orgId, new ComplianceReportRequest(
                ComplianceReportType.DEPLOYMENT_METRICS, from, from.plus(Duration.ofDays(367)), null)))
                .isInstanceOf(InvalidReportPeriodException.class);
        verify(pipelineAdminService, never()).list(any(), any());
    }
}
