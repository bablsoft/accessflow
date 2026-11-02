package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.compliance.api.Approver;
import com.bablsoft.accessflow.compliance.api.ClassifiedAccessReportRow;
import com.bablsoft.accessflow.compliance.api.ComplianceReport;
import com.bablsoft.accessflow.compliance.api.DeploymentMetricsReportRow;
import com.bablsoft.accessflow.compliance.api.MatchedClassification;
import com.bablsoft.accessflow.compliance.api.RegulatoryAuditTrailRow;
import com.bablsoft.accessflow.compliance.api.RetentionAdherenceReportRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Renders a {@link ComplianceReport} as an RFC 4180 CSV document (#459). Owns its own escaping copy
 * because {@code audit.internal.CsvWriter} cannot be imported across the Spring Modulith boundary.
 */
@Component
class ComplianceCsvWriter {

    private static final String LINE = "\r\n";
    private static final String MULTI_SEP = "; ";

    byte[] write(ComplianceReport report) {
        var sb = new StringBuilder();
        switch (report.type()) {
            case CLASSIFIED_ACCESS -> writeClassifiedAccess(sb, report.classifiedAccess());
            case REGULATORY_AUDIT_TRAIL -> writeAuditTrail(sb, report.auditTrail());
            case RETENTION_ADHERENCE -> writeRetentionAdherence(sb, report.retentionAdherence());
            case DEPLOYMENT_METRICS -> writeDeploymentMetrics(sb, report.deploymentMetrics());
        }
        return sb.toString().getBytes(UTF_8);
    }

    private void writeClassifiedAccess(StringBuilder sb, List<ClassifiedAccessReportRow> rows) {
        writeRow(sb, List.of("query_request_id", "datasource_id", "datasource_name",
                "submitter_email", "query_type", "referenced_tables", "matched_classifications",
                "rows_affected", "executed_at"));
        for (var row : rows) {
            writeRow(sb, List.of(
                    str(row.queryRequestId()),
                    str(row.datasourceId()),
                    nullToEmpty(row.datasourceName()),
                    nullToEmpty(row.submitterEmail()),
                    str(row.queryType()),
                    String.join(MULTI_SEP, row.referencedTables()),
                    formatMatched(row.matched()),
                    str(row.rowsAffected()),
                    str(row.executedAt())));
        }
    }

    private void writeAuditTrail(StringBuilder sb, List<RegulatoryAuditTrailRow> rows) {
        writeRow(sb, List.of("query_request_id", "datasource_id", "datasource_name",
                "submitter_email", "query_type", "sql_text", "effective_sql", "approvers",
                "executed_at"));
        for (var row : rows) {
            writeRow(sb, List.of(
                    str(row.queryRequestId()),
                    str(row.datasourceId()),
                    nullToEmpty(row.datasourceName()),
                    nullToEmpty(row.submitterEmail()),
                    str(row.queryType()),
                    nullToEmpty(row.sqlText()),
                    nullToEmpty(row.effectiveSql()),
                    formatApprovers(row.approvers()),
                    str(row.executedAt())));
        }
    }

    private void writeRetentionAdherence(StringBuilder sb, List<RetentionAdherenceReportRow> rows) {
        writeRow(sb, List.of("run_id", "datasource_id", "datasource_name", "kind", "action",
                "status", "method", "affected_rows", "policy_id", "deletion_request_id",
                "finished_at", "created_at"));
        for (var row : rows) {
            writeRow(sb, List.of(
                    str(row.runId()),
                    str(row.datasourceId()),
                    nullToEmpty(row.datasourceName()),
                    nullToEmpty(row.kind()),
                    nullToEmpty(row.action()),
                    nullToEmpty(row.status()),
                    nullToEmpty(row.method()),
                    str(row.affectedRows()),
                    str(row.policyId()),
                    str(row.deletionRequestId()),
                    str(row.finishedAt()),
                    str(row.createdAt())));
        }
    }

    private void writeDeploymentMetrics(StringBuilder sb, List<DeploymentMetricsReportRow> rows) {
        writeRow(sb, List.of("pipeline_id", "pipeline_name", "environment_id", "environment_name",
                "deployments", "lead_time_p50_seconds", "lead_time_p95_seconds", "lead_time_source",
                "change_failure_rate", "failed", "rolled_back", "unreported",
                "time_to_restore_p50_seconds", "time_to_restore_p95_seconds", "open_incidents",
                "approval_latency_p50_seconds", "approval_latency_p95_seconds", "timeout_rate",
                "timed_out", "break_glass_count"));
        for (var row : rows) {
            writeRow(sb, List.of(
                    str(row.pipelineId()),
                    nullToEmpty(row.pipelineName()),
                    str(row.environmentId()),
                    nullToEmpty(row.environmentName()),
                    str(row.deployments()),
                    str(row.leadTimeP50Seconds()),
                    str(row.leadTimeP95Seconds()),
                    nullToEmpty(row.leadTimeSource()),
                    decimal(row.changeFailureRate()),
                    str(row.failed()),
                    str(row.rolledBack()),
                    str(row.unreported()),
                    str(row.timeToRestoreP50Seconds()),
                    str(row.timeToRestoreP95Seconds()),
                    str(row.openIncidents()),
                    str(row.approvalLatencyP50Seconds()),
                    str(row.approvalLatencyP95Seconds()),
                    decimal(row.timeoutRate()),
                    str(row.timedOut()),
                    str(row.breakGlassCount())));
        }
    }

    /** A rate as a plain decimal ({@code 0.125}) — never scientific notation or a percent. */
    static String decimal(Double value) {
        return value == null ? "" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String formatMatched(List<MatchedClassification> matched) {
        return matched.stream()
                .map(m -> m.columnName() == null
                        ? m.tableName() + ":" + m.classification()
                        : m.tableName() + "." + m.columnName() + ":" + m.classification())
                .collect(Collectors.joining(MULTI_SEP));
    }

    private static String formatApprovers(List<Approver> approvers) {
        return approvers.stream()
                .map(a -> {
                    var name = a.displayName() == null ? a.email() : a.displayName();
                    return a.email() == null ? String.valueOf(name) : name + " <" + a.email() + ">";
                })
                .collect(Collectors.joining(MULTI_SEP));
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void writeRow(StringBuilder sb, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(fields.get(i)));
        }
        sb.append(LINE);
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean mustQuote = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                mustQuote = true;
                break;
            }
        }
        if (!mustQuote) {
            return value;
        }
        var sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                sb.append('"').append('"');
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
