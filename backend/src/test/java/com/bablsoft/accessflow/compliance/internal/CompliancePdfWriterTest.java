package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.compliance.api.Approver;
import com.bablsoft.accessflow.compliance.api.ClassifiedAccessReportRow;
import com.bablsoft.accessflow.compliance.api.ComplianceReport;
import com.bablsoft.accessflow.compliance.api.ComplianceReportType;
import com.bablsoft.accessflow.compliance.api.MatchedClassification;
import com.bablsoft.accessflow.compliance.api.RegulatoryAuditTrailRow;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.QueryType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

class CompliancePdfWriterTest {

    private final CompliancePdfWriter writer = new CompliancePdfWriter();
    private final Instant t = Instant.parse("2026-05-01T10:00:00Z");

    private String extractText(byte[] pdf) throws IOException {
        try (var doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void rendersClassifiedAccessPdfWithMagicAndContent() throws IOException {
        var report = new ComplianceReport(ComplianceReportType.CLASSIFIED_ACCESS, UUID.randomUUID(),
                t, t, t, null,
                List.of(new ClassifiedAccessReportRow(UUID.randomUUID(), UUID.randomUUID(), "ProdDb",
                        UUID.randomUUID(), "alice@example.com", QueryType.SELECT,
                        List.of("public.customers"),
                        List.of(new MatchedClassification("customers", "ssn", DataClassification.PII)),
                        5L, t)),
                List.of(), false);

        var pdf = writer.write(report);

        assertThat(new String(pdf, 0, 5, US_ASCII)).isEqualTo("%PDF-");
        var text = extractText(pdf);
        assertThat(text).contains("Classified Data Access");
        assertThat(text).contains("alice@example.com");
        assertThat(text).contains("ProdDb");
    }

    @Test
    void rendersRegulatoryTrailPdf() throws IOException {
        var report = new ComplianceReport(ComplianceReportType.REGULATORY_AUDIT_TRAIL, UUID.randomUUID(),
                t, t, t, null, List.of(),
                List.of(new RegulatoryAuditTrailRow(UUID.randomUUID(), UUID.randomUUID(), "ProdDb",
                        UUID.randomUUID(), "alice@example.com", QueryType.DELETE,
                        "DELETE FROM users", List.of(new Approver("rev@x.com", "Rev", "APPROVED", t)), t)),
                false);

        var pdf = writer.write(report);

        var text = extractText(pdf);
        assertThat(text).contains("Regulatory Audit Trail");
        assertThat(text).contains("Rev");
    }

    @Test
    void rendersEmptyReportWithNoRecordsNotice() throws IOException {
        var report = new ComplianceReport(ComplianceReportType.CLASSIFIED_ACCESS, UUID.randomUUID(),
                t, t, t, null, List.of(), List.of(), true);

        var text = extractText(writer.write(report));

        assertThat(text).contains("No records for the selected period.");
        assertThat(text).contains("truncated");
    }

    @Test
    void paginatesAcrossManyRowsAndWrapsLongSql() throws IOException {
        var rows = new ArrayList<RegulatoryAuditTrailRow>();
        var longSql = "DELETE FROM very_long_table_name WHERE "
                + "some_extremely_long_column_identifier_that_forces_wrapping = 'value' AND another = 2";
        for (int i = 0; i < 80; i++) {
            rows.add(new RegulatoryAuditTrailRow(UUID.randomUUID(), UUID.randomUUID(), "ProdDb",
                    UUID.randomUUID(), "user" + i + "@example.com", QueryType.DELETE, longSql,
                    List.of(new Approver("rev@x.com", "Rev", "APPROVED", t)), t));
        }
        var report = new ComplianceReport(ComplianceReportType.REGULATORY_AUDIT_TRAIL, UUID.randomUUID(),
                t, t, t, null, List.of(), rows, false);

        var pdf = writer.write(report);

        try (var doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
        }
    }
}
