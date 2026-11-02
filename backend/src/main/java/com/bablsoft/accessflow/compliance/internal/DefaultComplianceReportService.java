package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.compliance.api.ComplianceReport;
import com.bablsoft.accessflow.compliance.api.ComplianceReportRequest;
import com.bablsoft.accessflow.compliance.api.ComplianceReportService;
import com.bablsoft.accessflow.compliance.api.DeploymentMetricsReportRow;
import com.bablsoft.accessflow.compliance.api.InvalidReportPeriodException;
import com.bablsoft.accessflow.compliance.api.RegulatoryAuditTrailRow;
import com.bablsoft.accessflow.compliance.api.RetentionAdherenceReportRow;
import com.bablsoft.accessflow.compliance.internal.config.ComplianceProperties;
import com.bablsoft.accessflow.core.api.DataClassificationAdminService;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SortOrder;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentLookupService;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentView;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsService;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;
import com.bablsoft.accessflow.deploygov.api.InvalidDeploymentMetricsPeriodException;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DefaultComplianceReportService implements ComplianceReportService {

    private static final Set<QueryType> REGULATORY_TYPES = Set.of(QueryType.DDL, QueryType.DELETE);
    private static final int DATASOURCE_NAME_PAGE_SIZE = 1000;
    private static final int PIPELINE_PAGE_SIZE = 100;
    // Mirrors the deployment-metrics service's own window cap, so an organization without any
    // pipeline gets the same 400 as one with pipelines when the operator raised max-report-period.
    private static final Duration DEPLOYMENT_METRICS_MAX_PERIOD = Duration.ofDays(366);
    private static final String INVALID_PERIOD = "error.invalid_report_period";

    private final QuerySnapshotService snapshotService;
    private final DataClassificationAdminService dataClassificationAdminService;
    private final DatasourceAdminService datasourceAdminService;
    private final UserAdminService userAdminService;
    private final ReviewDecisionsParser reviewDecisionsParser;
    private final com.bablsoft.accessflow.lifecycle.api.LifecycleRunLookupService lifecycleRunLookupService;
    private final DeploymentPipelineAdminService pipelineAdminService;
    private final DeploymentEnvironmentLookupService environmentLookupService;
    private final DeploymentMetricsService deploymentMetricsService;
    private final ComplianceProperties properties;
    private final Clock clock;

    @Override
    public ComplianceReport generate(UUID organizationId, ComplianceReportRequest request) {
        validatePeriod(request);
        int cap = properties.maxRows();
        return switch (request.type()) {
            case CLASSIFIED_ACCESS -> classifiedAccess(organizationId, request, cap);
            case REGULATORY_AUDIT_TRAIL -> regulatoryTrail(organizationId, request, cap);
            case RETENTION_ADHERENCE -> retentionAdherence(organizationId, request, cap);
            case DEPLOYMENT_METRICS -> deploymentMetrics(organizationId, request, cap);
        };
    }

    /**
     * One row per (pipeline, environment), each computed by the deploygov metrics service narrowed
     * to that pair — the compliance module never re-derives a metric. Not datasource-scoped, so the
     * request's {@code datasourceId} is ignored and the report carries none.
     */
    private ComplianceReport deploymentMetrics(UUID organizationId, ComplianceReportRequest request,
                                               int cap) {
        if (!request.from().isBefore(request.to()) || Duration.between(request.from(), request.to())
                .compareTo(DEPLOYMENT_METRICS_MAX_PERIOD) > 0) {
            throw new InvalidReportPeriodException(INVALID_PERIOD);
        }
        var rows = new ArrayList<DeploymentMetricsReportRow>();
        boolean truncated = false;
        try {
            for (var pipeline : pipelines(organizationId)) {
                // The pipeline came from the org-scoped listing; a pipeline deleted since then
                // simply lists no environments.
                for (var environment : environmentLookupService.listByPipeline(pipeline.id())) {
                    if (rows.size() == cap) {
                        truncated = true;
                        break;
                    }
                    var metrics = deploymentMetricsService.organizationMetrics(organizationId,
                            new DeploymentMetricsFilter(request.from(), request.to(), null,
                                    pipeline.id(), environment.id()));
                    rows.add(toRow(pipeline, environment, metrics));
                }
                if (truncated) {
                    break;
                }
            }
        } catch (InvalidDeploymentMetricsPeriodException ex) {
            throw new InvalidReportPeriodException(INVALID_PERIOD);
        }
        return new ComplianceReport(request.type(), organizationId, request.from(), request.to(),
                Instant.now(clock), null, List.of(), List.of(), List.of(), rows, truncated);
    }

    private List<DeploymentPipelineView> pipelines(UUID organizationId) {
        var pipelines = new ArrayList<DeploymentPipelineView>();
        // The database sort only keeps paging stable; the report order is the in-memory sort below.
        for (int page = 0; ; page++) {
            var result = pipelineAdminService.list(organizationId, PageRequest.of(page,
                    PIPELINE_PAGE_SIZE, SortOrder.asc("name"), SortOrder.asc("id")));
            pipelines.addAll(result.content());
            if (page + 1 >= result.totalPages()) {
                break;
            }
        }
        pipelines.sort(Comparator.comparing(DeploymentPipelineView::name,
                String.CASE_INSENSITIVE_ORDER).thenComparing(DeploymentPipelineView::id));
        return pipelines;
    }

    private static DeploymentMetricsReportRow toRow(DeploymentPipelineView pipeline,
                                                    DeploymentEnvironmentView environment,
                                                    DeploymentMetricsView metrics) {
        var leadTime = metrics.leadTime();
        var failure = metrics.changeFailureRate();
        var restore = metrics.timeToRestore();
        var approval = metrics.approvalLatency();
        var timeout = metrics.timeoutRate();
        return new DeploymentMetricsReportRow(
                pipeline.id(),
                pipeline.name(),
                environment.id(),
                environment.name(),
                metrics.deploymentFrequency().total(),
                leadTime.p50Seconds(),
                leadTime.p95Seconds(),
                leadTime.source() == null ? null : leadTime.source().name(),
                failure.rate(),
                failure.failed(),
                failure.rolledBack(),
                failure.unreported(),
                restore.p50Seconds(),
                restore.p95Seconds(),
                restore.openIncidents(),
                approval.p50Seconds(),
                approval.p95Seconds(),
                timeout.rate(),
                timeout.timedOut(),
                metrics.breakGlassCount());
    }

    private ComplianceReport retentionAdherence(UUID organizationId, ComplianceReportRequest request,
                                                int cap) {
        var raw = lifecycleRunLookupService.findForPeriod(organizationId, request.from(),
                request.to(), request.datasourceId(), cap + 1);
        boolean truncated = raw.size() > cap;
        var runs = truncated ? raw.subList(0, cap) : raw;

        var datasourceNames = datasourceNames(organizationId);
        var rows = new ArrayList<RetentionAdherenceReportRow>(runs.size());
        for (var run : runs) {
            rows.add(new RetentionAdherenceReportRow(
                    run.id(),
                    run.datasourceId(),
                    datasourceNames.get(run.datasourceId()),
                    run.kind().name(),
                    run.action().name(),
                    run.status().name(),
                    run.method(),
                    run.affectedRows(),
                    run.policyId(),
                    run.deletionRequestId(),
                    run.finishedAt(),
                    run.createdAt()));
        }
        return new ComplianceReport(request.type(), organizationId, request.from(), request.to(),
                Instant.now(clock), request.datasourceId(), List.of(), List.of(), rows, truncated);
    }

    private ComplianceReport classifiedAccess(UUID organizationId, ComplianceReportRequest request,
                                              int cap) {
        var raw = snapshotService.findForPeriod(organizationId, request.from(), request.to(),
                request.datasourceId(), null, cap + 1);
        boolean truncated = raw.size() > cap;
        var snapshots = truncated ? raw.subList(0, cap) : raw;

        var classifications = dataClassificationAdminService.listForOrganization(organizationId);
        var emails = submitterEmails(organizationId, snapshots);
        var datasourceNames = datasourceNames(organizationId);
        var rows = ClassificationJoiner.join(snapshots, classifications, emails, datasourceNames);

        return new ComplianceReport(request.type(), organizationId, request.from(), request.to(),
                Instant.now(clock), request.datasourceId(), rows, List.of(), List.of(), truncated);
    }

    private ComplianceReport regulatoryTrail(UUID organizationId, ComplianceReportRequest request,
                                             int cap) {
        var raw = snapshotService.findForPeriod(organizationId, request.from(), request.to(),
                request.datasourceId(), REGULATORY_TYPES, cap + 1);
        boolean truncated = raw.size() > cap;
        var snapshots = truncated ? raw.subList(0, cap) : raw;

        var emails = submitterEmails(organizationId, snapshots);
        var datasourceNames = datasourceNames(organizationId);

        var rows = new ArrayList<RegulatoryAuditTrailRow>(snapshots.size());
        for (var snapshot : snapshots) {
            rows.add(new RegulatoryAuditTrailRow(
                    snapshot.queryRequestId(),
                    snapshot.datasourceId(),
                    datasourceNames.get(snapshot.datasourceId()),
                    snapshot.submittedBy(),
                    emails.get(snapshot.submittedBy()),
                    snapshot.queryType(),
                    snapshot.sqlText(),
                    reviewDecisionsParser.approvers(snapshot.reviewDecisionsJson()),
                    snapshot.executedAt(),
                    snapshot.effectiveSql()));
        }

        return new ComplianceReport(request.type(), organizationId, request.from(), request.to(),
                Instant.now(clock), request.datasourceId(), List.of(), rows, List.of(), truncated);
    }

    private void validatePeriod(ComplianceReportRequest request) {
        if (request.from() == null || request.to() == null
                || request.from().isAfter(request.to())) {
            throw new InvalidReportPeriodException(INVALID_PERIOD);
        }
        if (Duration.between(request.from(), request.to()).compareTo(properties.maxReportPeriod()) > 0) {
            throw new InvalidReportPeriodException(INVALID_PERIOD);
        }
    }

    private Map<UUID, String> submitterEmails(UUID organizationId, List<QuerySnapshotView> snapshots) {
        Set<UUID> ids = new HashSet<>();
        for (var snapshot : snapshots) {
            if (snapshot.submittedBy() != null) {
                ids.add(snapshot.submittedBy());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> emails = new LinkedHashMap<>();
        userAdminService.findByIds(organizationId, ids)
                .forEach((id, user) -> emails.put(id, user.email()));
        return emails;
    }

    private Map<UUID, String> datasourceNames(UUID organizationId) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (DatasourceView ds : datasourceAdminService
                .listForAdmin(organizationId, PageRequest.of(0, DATASOURCE_NAME_PAGE_SIZE)).content()) {
            names.put(ds.id(), ds.name());
        }
        return names;
    }
}
