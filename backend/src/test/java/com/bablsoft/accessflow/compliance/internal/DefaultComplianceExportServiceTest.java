package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.compliance.api.ComplianceReport;
import com.bablsoft.accessflow.compliance.api.ComplianceReportFormat;
import com.bablsoft.accessflow.compliance.api.ComplianceReportRequest;
import com.bablsoft.accessflow.compliance.api.ComplianceReportService;
import com.bablsoft.accessflow.compliance.api.ComplianceReportType;
import com.bablsoft.accessflow.security.api.ExportSignatureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultComplianceExportServiceTest {

    @Mock ComplianceReportService reportService;
    @Mock CompliancePdfWriter pdfWriter;
    @Mock ComplianceCsvWriter csvWriter;
    @Mock ExportSignatureService signatureService;
    @Mock AuditLogService auditLogService;

    private DefaultComplianceExportService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final Instant from = Instant.parse("2026-04-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-07-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(Instant.parse("2026-07-02T09:00:00Z"), ZoneOffset.UTC);
        service = new DefaultComplianceExportService(reportService, pdfWriter, csvWriter,
                signatureService, auditLogService, clock);
    }

    private ComplianceReport report(boolean truncated) {
        return new ComplianceReport(ComplianceReportType.CLASSIFIED_ACCESS, orgId, from, to,
                Instant.parse("2026-07-02T09:00:00Z"), null, List.of(), List.of(), truncated);
    }

    private ComplianceReportRequest request() {
        return new ComplianceReportRequest(ComplianceReportType.CLASSIFIED_ACCESS, from, to, null);
    }

    @Test
    void pdfExportSignsAndChainsHashIntoAudit() {
        when(reportService.generate(orgId, request())).thenReturn(report(true));
        when(pdfWriter.write(any())).thenReturn(new byte[]{1, 2, 3});
        when(signatureService.sign(any())).thenReturn("base64sig");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");

        var export = service.export(orgId, request(), ComplianceReportFormat.PDF, actorId, "1.2.3.4", "curl");

        assertThat(export.filename()).endsWith(".pdf").startsWith("compliance-classified-access-");
        assertThat(export.contentType()).isEqualTo("application/pdf");
        assertThat(export.signatureBase64()).isEqualTo("base64sig");
        assertThat(export.signatureAlgorithm()).isEqualTo("SHA256withRSA");
        assertThat(export.contentSha256Hex()).hasSize(64);
        assertThat(export.truncated()).isTrue();
        verify(csvWriter, never()).write(any());

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.COMPLIANCE_REPORT_EXPORTED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.COMPLIANCE_REPORT);
        assertThat(entry.organizationId()).isEqualTo(orgId);
        assertThat(entry.actorId()).isEqualTo(actorId);
        assertThat(entry.metadata())
                .containsEntry("content_sha256", export.contentSha256Hex())
                .containsEntry("signature", "base64sig")
                .containsEntry("signature_algorithm", "SHA256withRSA")
                .containsEntry("format", "PDF")
                .containsEntry("truncated", true);
        assertThat(entry.ipAddress()).isEqualTo("1.2.3.4");
    }

    @Test
    void csvExportUsesCsvWriter() {
        when(reportService.generate(orgId, request())).thenReturn(report(false));
        when(csvWriter.write(any())).thenReturn("a,b\r\n".getBytes());
        when(signatureService.sign(any())).thenReturn("sig");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");

        var export = service.export(orgId, request(), ComplianceReportFormat.CSV, actorId, null, null);

        assertThat(export.filename()).endsWith(".csv");
        assertThat(export.contentType()).contains("text/csv");
        verify(pdfWriter, never()).write(any());
    }

    @Test
    void auditWriteFailurePropagatesAndFailsExport() {
        when(reportService.generate(orgId, request())).thenReturn(report(false));
        when(csvWriter.write(any())).thenReturn(new byte[]{9});
        when(signatureService.sign(any())).thenReturn("sig");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");
        doThrow(new RuntimeException("audit chain write failed")).when(auditLogService).record(any());

        assertThatThrownBy(() ->
                service.export(orgId, request(), ComplianceReportFormat.CSV, actorId, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("audit chain write failed");
    }
}
