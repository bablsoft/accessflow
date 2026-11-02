package com.bablsoft.accessflow.compliance.internal.web;

import com.bablsoft.accessflow.compliance.api.ComplianceReport;
import com.bablsoft.accessflow.compliance.api.ComplianceReportType;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.QueryType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HTTP response envelope for a compliance report (#459). Serialized snake_case via the global
 * Jackson naming strategy. Maps the {@code compliance.api} report records — entities/api DTOs are
 * never returned directly.
 */
public record ComplianceReportResponse(
        ComplianceReportType type,
        UUID organizationId,
        Instant periodFrom,
        Instant periodTo,
        Instant generatedAt,
        UUID datasourceId,
        List<ClassifiedAccessRow> classifiedAccess,
        List<RegulatoryAuditTrailRow> auditTrail,
        List<RetentionAdherenceRow> retentionAdherence,
        List<DeploymentMetricsRow> deploymentMetrics,
        int rowCount,
        boolean truncated) {

    public record MatchedClassification(String tableName, String columnName,
                                        DataClassification classification) {
    }

    public record ClassifiedAccessRow(
            UUID queryRequestId,
            UUID datasourceId,
            String datasourceName,
            UUID submittedBy,
            String submitterEmail,
            QueryType queryType,
            List<String> referencedTables,
            List<MatchedClassification> matched,
            Long rowsAffected,
            Instant executedAt) {
    }

    public record Approver(String email, String displayName, String decision, Instant decidedAt) {
    }

    public record RegulatoryAuditTrailRow(
            UUID queryRequestId,
            UUID datasourceId,
            String datasourceName,
            UUID submittedBy,
            String submitterEmail,
            QueryType queryType,
            String sqlText,
            List<Approver> approvers,
            Instant executedAt,
            String effectiveSql) {
    }

    public record RetentionAdherenceRow(
            UUID runId,
            UUID datasourceId,
            String datasourceName,
            String kind,
            String action,
            String status,
            String method,
            long affectedRows,
            UUID policyId,
            UUID deletionRequestId,
            Instant finishedAt,
            Instant createdAt) {
    }

    public record DeploymentMetricsRow(
            UUID pipelineId,
            String pipelineName,
            UUID environmentId,
            String environmentName,
            long deployments,
            Long leadTimeP50Seconds,
            Long leadTimeP95Seconds,
            String leadTimeSource,
            Double changeFailureRate,
            long failed,
            long rolledBack,
            long unreported,
            Long timeToRestoreP50Seconds,
            Long timeToRestoreP95Seconds,
            long openIncidents,
            Long approvalLatencyP50Seconds,
            Long approvalLatencyP95Seconds,
            Double timeoutRate,
            long timedOut,
            long breakGlassCount) {
    }

    public static ComplianceReportResponse from(ComplianceReport report) {
        var classified = report.classifiedAccess().stream()
                .map(r -> new ClassifiedAccessRow(
                        r.queryRequestId(), r.datasourceId(), r.datasourceName(), r.submittedBy(),
                        r.submitterEmail(), r.queryType(), r.referencedTables(),
                        r.matched().stream()
                                .map(m -> new MatchedClassification(m.tableName(), m.columnName(),
                                        m.classification()))
                                .toList(),
                        r.rowsAffected(), r.executedAt()))
                .toList();
        var trail = report.auditTrail().stream()
                .map(r -> new RegulatoryAuditTrailRow(
                        r.queryRequestId(), r.datasourceId(), r.datasourceName(), r.submittedBy(),
                        r.submitterEmail(), r.queryType(), r.sqlText(),
                        r.approvers().stream()
                                .map(a -> new Approver(a.email(), a.displayName(), a.decision(),
                                        a.decidedAt()))
                                .toList(),
                        r.executedAt(), r.effectiveSql()))
                .toList();
        var retention = report.retentionAdherence().stream()
                .map(r -> new RetentionAdherenceRow(
                        r.runId(), r.datasourceId(), r.datasourceName(), r.kind(), r.action(),
                        r.status(), r.method(), r.affectedRows(), r.policyId(),
                        r.deletionRequestId(), r.finishedAt(), r.createdAt()))
                .toList();
        var deployments = report.deploymentMetrics().stream()
                .map(r -> new DeploymentMetricsRow(
                        r.pipelineId(), r.pipelineName(), r.environmentId(), r.environmentName(),
                        r.deployments(), r.leadTimeP50Seconds(), r.leadTimeP95Seconds(),
                        r.leadTimeSource(), r.changeFailureRate(), r.failed(), r.rolledBack(),
                        r.unreported(), r.timeToRestoreP50Seconds(), r.timeToRestoreP95Seconds(),
                        r.openIncidents(), r.approvalLatencyP50Seconds(),
                        r.approvalLatencyP95Seconds(), r.timeoutRate(), r.timedOut(),
                        r.breakGlassCount()))
                .toList();
        return new ComplianceReportResponse(report.type(), report.organizationId(),
                report.periodFrom(), report.periodTo(), report.generatedAt(), report.datasourceId(),
                classified, trail, retention, deployments, report.rowCount(), report.truncated());
    }
}
