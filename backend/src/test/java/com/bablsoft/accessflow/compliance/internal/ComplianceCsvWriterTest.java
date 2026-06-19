package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.compliance.api.Approver;
import com.bablsoft.accessflow.compliance.api.ClassifiedAccessReportRow;
import com.bablsoft.accessflow.compliance.api.ComplianceReport;
import com.bablsoft.accessflow.compliance.api.ComplianceReportType;
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
                        List.of(new Approver("rev@x.com", "Rev", "APPROVED", t)), t)),
                false);

        var csv = new String(writer.write(report), UTF_8);

        assertThat(csv).startsWith("query_request_id,datasource_id,datasource_name");
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
}
