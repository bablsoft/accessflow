package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.compliance.api.Approver;
import com.bablsoft.accessflow.compliance.api.ClassifiedAccessReportRow;
import com.bablsoft.accessflow.compliance.api.ComplianceReport;
import com.bablsoft.accessflow.compliance.api.ComplianceReportType;
import com.bablsoft.accessflow.compliance.api.DeploymentMetricsReportRow;
import com.bablsoft.accessflow.compliance.api.MatchedClassification;
import com.bablsoft.accessflow.compliance.api.RegulatoryAuditTrailRow;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ComplianceCsvWriterTest {

    private final ComplianceCsvWriter writer = new ComplianceCsvWriter();
    private final Instant t = Instant.parse("2026-05-01T10:00:00Z");

    @Test
    void writesClassifiedAccessHeaderAndRowsWithRfc4180Escaping() {
        var report = new ComplianceReport(ComplianceReportType.CLASSIFIED_ACCESS, UUID.randomUUID(),
                t, t, t, null,
                List.of(new ClassifiedAccessReportRow(UUID.randomUUID(), UUID.randomUUID(),
                        "Prod, Inc", UUID.randomUUID(), "a@x.com", QueryType.SELECT,
                        List.of("public.customers"),
                        List.of(new MatchedClassification("customers", "ssn", DataClassification.PII)),
                        5L, t)),
                List.of(), false);

        var csv = new String(writer.write(report), UTF_8);

        assertThat(csv).startsWith("query_request_id,datasource_id,datasource_name");
        assertThat(csv).contains("\"Prod, Inc\""); // comma forces quoting
        assertThat(csv).contains("customers.ssn:PII");
        assertThat(csv).contains("a@x.com");
        assertThat(csv).contains("\r\n");
    }

    @Test
    void writesRegulatoryTrailWithApproversAndQuotedSql() {
        var report = new ComplianceReport(ComplianceReportType.REGULATORY_AUDIT_TRAIL, UUID.randomUUID(),
                t, t, t, null, List.of(),
                List.of(new RegulatoryAuditTrailRow(UUID.randomUUID(), UUID.randomUUID(), "Prod",
                        UUID.randomUUID(), "a@x.com", QueryType.DELETE,
                        "DELETE FROM users WHERE id = 1",
                        List.of(new Approver("rev@x.com", "Rev", "APPROVED", t)), t,
                        "DELETE FROM users WHERE id = 1 AND tenant = ?")),
                false);

        var csv = new String(writer.write(report), UTF_8);

        assertThat(csv).startsWith("query_request_id,datasource_id,datasource_name,"
                + "submitter_email,query_type,sql_text,effective_sql,approvers,executed_at");
        assertThat(csv).contains("DELETE FROM users WHERE id = 1 AND tenant = ?");
        assertThat(csv).contains("DELETE FROM users WHERE id = 1");
        assertThat(csv).contains("Rev <rev@x.com>");
    }

    @Test
    void emptyReportStillWritesHeader() {
        var report = new ComplianceReport(ComplianceReportType.CLASSIFIED_ACCESS, UUID.randomUUID(),
                t, t, t, null, List.of(), List.of(), false);

        var csv = new String(writer.write(report), UTF_8);

        assertThat(csv.strip()).isEqualTo("query_request_id,datasource_id,datasource_name,"
                + "submitter_email,query_type,referenced_tables,matched_classifications,"
                + "rows_affected,executed_at");
    }

    @Test
    void writesDeploymentMetricsHeaderInDocumentedOrderWithDecimalRatesAndEmptyNulls() {
        var pipelineId = UUID.randomUUID();
        var envId = UUID.randomUUID();
        var report = new ComplianceReport(ComplianceReportType.DEPLOYMENT_METRICS, UUID.randomUUID(),
                t, t.plusSeconds(86_400), t, null, List.of(), List.of(), List.of(),
                List.of(new DeploymentMetricsReportRow(pipelineId, "payments, api", envId, "prod", 8,
                600L, 3600L, "MIXED", 0.125, 1, 0, 2, 120L, 900L, 1, 60L, 300L, 0.00005, 1, 2),
                        new DeploymentMetricsReportRow(pipelineId, "payments", envId, "dev", 0, null,
                null, null, null, 0, 0, 0, null, null, 0, null, null, null, 0, 0)),
                false);

        var lines = new String(writer.write(report), UTF_8).split("\r\n");

        assertThat(lines[0]).isEqualTo("pipeline_id,pipeline_name,environment_id,environment_name,"
                + "deployments,lead_time_p50_seconds,lead_time_p95_seconds,lead_time_source,"
                + "change_failure_rate,failed,rolled_back,unreported,time_to_restore_p50_seconds,"
                + "time_to_restore_p95_seconds,open_incidents,approval_latency_p50_seconds,"
                + "approval_latency_p95_seconds,timeout_rate,timed_out,break_glass_count");
        assertThat(lines[1]).isEqualTo(pipelineId + ",\"payments, api\"," + envId
                + ",prod,8,600,3600,MIXED,0.125,1,0,2,120,900,1,60,300,0.00005,1,2");
        assertThat(lines[2]).isEqualTo(pipelineId + ",payments," + envId
                + ",dev,0,,,,,0,0,0,,,0,,,,0,0");
        assertThat(lines).hasSize(3);
    }

    @Test
    void decimalRendersPlainDecimalsWithoutScientificNotation() {
        assertThat(ComplianceCsvWriter.decimal(null)).isEmpty();
        assertThat(ComplianceCsvWriter.decimal(0.125)).isEqualTo("0.125");
        assertThat(ComplianceCsvWriter.decimal(1.0)).isEqualTo("1");
        assertThat(ComplianceCsvWriter.decimal(0.0)).isEqualTo("0");
        assertThat(ComplianceCsvWriter.decimal(1.25E-4)).isEqualTo("0.000125");
    }
}
