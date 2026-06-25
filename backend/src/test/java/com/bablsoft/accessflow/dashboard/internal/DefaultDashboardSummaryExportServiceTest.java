package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.dashboard.api.DashboardSummaryFormat;
import com.bablsoft.accessflow.dashboard.api.DashboardWeeklySummary;
import com.bablsoft.accessflow.security.api.ExportSignatureService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDashboardSummaryExportServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final DashboardWeeklySummaryBuilder builder = mock(DashboardWeeklySummaryBuilder.class);
    private final DashboardSummaryPdfWriter pdfWriter = mock(DashboardSummaryPdfWriter.class);
    private final DashboardSummaryCsvWriter csvWriter = mock(DashboardSummaryCsvWriter.class);
    private final ExportSignatureService signatureService = mock(ExportSignatureService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);

    private final DefaultDashboardSummaryExportService service =
            new DefaultDashboardSummaryExportService(builder, pdfWriter, csvWriter,
                    signatureService, auditLogService);

    private DashboardWeeklySummary summary() {
        return new DashboardWeeklySummary(ORG, USER, "u@x.io", "User",
                LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 29), 5,
                List.of(), List.of(), 2, 1, 3, Instant.parse("2026-06-25T12:00:00Z"));
    }

    @Test
    void exportPdfSignsAndAudits() {
        when(builder.build(ORG, USER, null)).thenReturn(summary());
        when(pdfWriter.write(any())).thenReturn(new byte[]{1, 2, 3});
        when(signatureService.sign(any())).thenReturn("sig==");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");

        var export = service.export(ORG, USER, null, DashboardSummaryFormat.PDF, USER, "1.2.3.4", "agent");

        assertThat(export.contentType()).isEqualTo("application/pdf");
        assertThat(export.filename()).isEqualTo("dashboard-summary-2026-06-22.pdf");
        assertThat(export.signatureBase64()).isEqualTo("sig==");
        assertThat(export.signatureAlgorithm()).isEqualTo("SHA256withRSA");
        assertThat(export.contentSha256Hex()).isNotBlank();

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditAction.DASHBOARD_SUMMARY_EXPORTED);
        assertThat(captor.getValue().resourceType()).isEqualTo(AuditResourceType.DASHBOARD_SUMMARY);
        assertThat(captor.getValue().metadata()).containsEntry("format", "PDF");
    }

    @Test
    void exportCsvUsesCsvWriter() {
        when(builder.build(eq(ORG), eq(USER), any())).thenReturn(summary());
        when(csvWriter.write(any())).thenReturn("a,b".getBytes());
        when(signatureService.sign(any())).thenReturn("s");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");

        var export = service.export(ORG, USER, LocalDate.of(2026, 6, 24),
                DashboardSummaryFormat.CSV, USER, null, null);

        assertThat(export.contentType()).isEqualTo("text/csv; charset=utf-8");
        assertThat(export.filename()).endsWith(".csv");
        verify(csvWriter).write(any());
    }
}
