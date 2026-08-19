package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.compliance.api.ComplianceReportFormat;
import com.bablsoft.accessflow.compliance.api.ExportDecision;
import com.bablsoft.accessflow.compliance.api.ResultExportDeniedException;
import com.bablsoft.accessflow.compliance.api.ResultExportNotFoundException;
import com.bablsoft.accessflow.compliance.api.ResultExportUnavailableException;
import com.bablsoft.accessflow.compliance.api.ResultExportWatermark;
import com.bablsoft.accessflow.compliance.internal.config.ComplianceProperties;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.security.api.ExportSignatureService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultResultExportServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-02T09:00:00Z");

    @Mock DefaultResultExportGovernanceService governanceService;
    @Mock QuerySnapshotService querySnapshotService;
    @Mock QueryResultPersistenceService queryResultPersistenceService;
    @Mock ResultExportCsvWriter csvWriter;
    @Mock ResultExportPdfWriter pdfWriter;
    @Mock ExportSignatureService signatureService;
    @Mock AuditLogService auditLogService;

    private DefaultResultExportService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID queryId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final List<String> columns = List.of("id", "name");
    private final List<List<Object>> threeRows =
            List.of(List.of(1, "a"), List.of(2, "b"), List.of(3, "c"));

    @BeforeEach
    void setUp() {
        service = newService(new ComplianceProperties(null, 100, 10, 2));
    }

    private DefaultResultExportService newService(ComplianceProperties properties) {
        return new DefaultResultExportService(governanceService, querySnapshotService,
                queryResultPersistenceService, csvWriter, pdfWriter, signatureService,
                auditLogService, properties, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private QuerySnapshotView snapshot(QueryType queryType) {
        return new QuerySnapshotView(UUID.randomUUID(), queryId, orgId, datasourceId,
                submitterId, "SELECT * FROM customers", queryType, false, DbType.POSTGRESQL,
                List.of("public.customers"), "hash", null, null, 3L, 10, NOW, NOW);
    }

    private QueryResultPersistenceService.QueryResultSnapshot result(String rowsJson,
                                                                     boolean truncated) {
        return new QueryResultPersistenceService.QueryResultSnapshot(queryId,
                "[{\"name\":\"id\"},{\"name\":\"name\"}]", rowsJson, 3, truncated, null, 5);
    }

    private ExportDecision allowAll() {
        return new ExportDecision(true, ExportPolicyMode.ALLOW, null, false, List.of(),
                List.of());
    }

    private void stubVisibleSelect(String rowsJson, ExportDecision decision) {
        when(querySnapshotService.find(queryId, orgId))
                .thenReturn(Optional.of(snapshot(QueryType.SELECT)));
        when(queryResultPersistenceService.find(queryId))
                .thenReturn(Optional.of(result(rowsJson, false)));
        when(governanceService.resultColumnNames(queryId)).thenReturn(columns);
        when(governanceService.decide(any(QuerySnapshotView.class), eq(columns),
                eq(submitterId))).thenReturn(decision);
    }

    // --- visibility / availability -------------------------------------------------------------

    @Test
    void exportThrowsNotFoundWhenNoSnapshot() {
        when(querySnapshotService.find(queryId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.export(orgId, queryId, ComplianceReportFormat.CSV,
                submitterId, "a@x.com", false, null, null))
                .isInstanceOf(ResultExportNotFoundException.class);
    }

    @Test
    void exportThrowsNotFoundForNonSubmitterWithoutQueryAdmin() {
        when(querySnapshotService.find(queryId, orgId))
                .thenReturn(Optional.of(snapshot(QueryType.SELECT)));
        var stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service.export(orgId, queryId, ComplianceReportFormat.CSV,
                stranger, "s@x.com", false, null, null))
                .isInstanceOf(ResultExportNotFoundException.class);
        verifyNoInteractions(queryResultPersistenceService);
    }

    @Test
    void queryAdminBypassesSubmitterCheck() {
        var admin = UUID.randomUUID();
        when(querySnapshotService.find(queryId, orgId))
                .thenReturn(Optional.of(snapshot(QueryType.SELECT)));
        when(queryResultPersistenceService.find(queryId))
                .thenReturn(Optional.of(result("[[1,\"a\"]]", false)));
        when(governanceService.resultColumnNames(queryId)).thenReturn(columns);
        when(governanceService.decide(any(QuerySnapshotView.class), eq(columns), eq(admin)))
                .thenReturn(allowAll());
        when(csvWriter.write(eq(columns), any(), isNull(), isNull()))
                .thenReturn("csv".getBytes(StandardCharsets.UTF_8));
        when(signatureService.sign(any())).thenReturn("sig");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");

        var export = service.export(orgId, queryId, ComplianceReportFormat.CSV, admin,
                "admin@x.com", true, null, null);

        assertThat(export.filename()).endsWith(".csv");
    }

    @Test
    void exportThrowsNotFoundWhenNoResultRow() {
        when(querySnapshotService.find(queryId, orgId))
                .thenReturn(Optional.of(snapshot(QueryType.SELECT)));
        when(queryResultPersistenceService.find(queryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.export(orgId, queryId, ComplianceReportFormat.CSV,
                submitterId, "a@x.com", false, null, null))
                .isInstanceOf(ResultExportNotFoundException.class);
    }

    @Test
    void exportThrowsNotFoundWhenRowsJsonUnparseable() {
        stubVisibleSelect("this is not json", allowAll());

        assertThatThrownBy(() -> service.export(orgId, queryId, ComplianceReportFormat.CSV,
                submitterId, "a@x.com", false, null, null))
                .isInstanceOf(ResultExportNotFoundException.class);
    }

    @Test
    void exportThrowsUnavailableForNonSelect() {
        when(querySnapshotService.find(queryId, orgId))
                .thenReturn(Optional.of(snapshot(QueryType.UPDATE)));

        assertThatThrownBy(() -> service.export(orgId, queryId, ComplianceReportFormat.CSV,
                submitterId, "a@x.com", false, null, null))
                .isInstanceOf(ResultExportUnavailableException.class);
    }

    // --- deny ----------------------------------------------------------------------------------

    @Test
    void deniedExportThrowsAndWritesNoAudit() {
        var denied = new ExportDecision(false, ExportPolicyMode.DENY_CLASSIFIED, null, false,
                List.of(UUID.randomUUID()), List.of(DataClassification.PII));
        stubVisibleSelect("[[1,\"a\"]]", denied);

        assertThatThrownBy(() -> service.export(orgId, queryId, ComplianceReportFormat.CSV,
                submitterId, "a@x.com", false, null, null))
                .isInstanceOf(ResultExportDeniedException.class)
                .satisfies(ex -> assertThat(
                        ((ResultExportDeniedException) ex).classificationsPresent())
                        .containsExactly(DataClassification.PII));
        verifyNoInteractions(auditLogService, csvWriter, pdfWriter, signatureService);
    }

    // --- happy CSV path ------------------------------------------------------------------------

    @Test
    void csvExportSignsRenderedBytesAndRecordsAudit() throws Exception {
        stubVisibleSelect("[[1,\"a\"],[2,\"b\"]]", allowAll());
        var rendered = "id,name\r\n1,a\r\n2,b\r\n".getBytes(StandardCharsets.UTF_8);
        when(csvWriter.write(eq(columns), eq(List.of(List.of(1, "a"), List.of(2, "b"))),
                isNull(), isNull())).thenReturn(rendered);
        when(signatureService.sign(any())).thenReturn("base64sig");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");

        var export = service.export(orgId, queryId, ComplianceReportFormat.CSV, submitterId,
                "a@x.com", false, "1.2.3.4", "curl");

        var expectedSha = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(rendered));
        assertThat(export.content()).isEqualTo(rendered);
        assertThat(export.contentSha256Hex()).isEqualTo(expectedSha);
        assertThat(export.signatureBase64()).isEqualTo("base64sig");
        assertThat(export.signatureAlgorithm()).isEqualTo("SHA256withRSA");
        assertThat(export.contentType()).isEqualTo("text/csv; charset=utf-8");
        assertThat(export.truncated()).isFalse();
        assertThat(export.filename()).isEqualTo("query-results-"
                + queryId.toString().substring(0, 8) + "-20260702T090000Z.csv");

        var signedCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(signatureService).sign(signedCaptor.capture());
        assertThat(signedCaptor.getValue()).isEqualTo(rendered);

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        var entry = auditCaptor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.RESULT_EXPORTED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
        assertThat(entry.resourceId()).isEqualTo(queryId);
        assertThat(entry.organizationId()).isEqualTo(orgId);
        assertThat(entry.actorId()).isEqualTo(submitterId);
        assertThat(entry.ipAddress()).isEqualTo("1.2.3.4");
        assertThat(entry.userAgent()).isEqualTo("curl");
        assertThat(entry.metadata())
                .containsEntry("trigger", "endpoint")
                .containsEntry("format", "CSV")
                .containsEntry("datasource_id", datasourceId.toString())
                .containsEntry("row_count", 2L)
                .containsEntry("truncated", false)
                .containsEntry("watermarked", false)
                .containsEntry("policy_ids", List.of())
                .containsEntry("classifications_present", List.of())
                .containsEntry("content_sha256", expectedSha)
                .containsEntry("signature", "base64sig")
                .containsEntry("signature_algorithm", "SHA256withRSA");

        verify(governanceService).publishIfSensitive(orgId, queryId, submitterId, "a@x.com",
                "CSV", 2, allowAll(), "endpoint");
        verifyNoInteractions(pdfWriter);
    }

    @Test
    void auditFailurePropagatesAndSuppressesSensitiveEvent() {
        stubVisibleSelect("[[1,\"a\"]]", allowAll());
        when(csvWriter.write(eq(columns), any(), isNull(), isNull()))
                .thenReturn("x".getBytes(StandardCharsets.UTF_8));
        when(signatureService.sign(any())).thenReturn("sig");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");
        doThrow(new RuntimeException("audit chain write failed"))
                .when(auditLogService).record(any());

        assertThatThrownBy(() -> service.export(orgId, queryId, ComplianceReportFormat.CSV,
                submitterId, "a@x.com", false, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("audit chain write failed");
        verify(governanceService, never()).publishIfSensitive(any(), any(), any(), anyString(),
                anyString(), anyLong(), any(), anyString());
    }

    // --- caps / truncation ---------------------------------------------------------------------

    @Test
    void pdfFormatUsesPdfCapAndPdfWriter() {
        stubVisibleSelect("[[1,\"a\"],[2,\"b\"],[3,\"c\"]]", allowAll());
        var cappedRows = threeRows.subList(0, 2);
        when(pdfWriter.write(eq(queryId), eq(columns), eq(cappedRows), isNull(), isNull(),
                eq("a@x.com"), eq(NOW), eq(true))).thenReturn("%PDF-".getBytes());
        when(signatureService.sign(any())).thenReturn("sig");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");

        var export = service.export(orgId, queryId, ComplianceReportFormat.PDF, submitterId,
                "a@x.com", false, null, null);

        assertThat(export.truncated()).isTrue();
        assertThat(export.contentType()).isEqualTo("application/pdf");
        assertThat(export.filename()).isEqualTo("query-results-"
                + queryId.toString().substring(0, 8) + "-20260702T090000Z.pdf");
        verifyNoInteractions(csvWriter);
    }

    @Test
    void csvFormatCapTruncatesWhenSmallerThanRowCount() {
        service = newService(new ComplianceProperties(null, 100, 2, 5));
        stubVisibleSelect("[[1,\"a\"],[2,\"b\"],[3,\"c\"]]", allowAll());
        when(csvWriter.write(eq(columns), eq(threeRows.subList(0, 2)), isNull(), isNull()))
                .thenReturn("csv".getBytes(StandardCharsets.UTF_8));
        when(signatureService.sign(any())).thenReturn("sig");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");

        var export = service.export(orgId, queryId, ComplianceReportFormat.CSV, submitterId,
                "a@x.com", false, null, null);

        assertThat(export.truncated()).isTrue();
    }

    @Test
    void policyRowCapSmallerThanFormatCapWinsAndWatermarksWriter() {
        var capped = new ExportDecision(true, ExportPolicyMode.ROW_CAP, 1, true,
                List.of(UUID.randomUUID()), List.of());
        stubVisibleSelect("[[1,\"a\"],[2,\"b\"],[3,\"c\"]]", capped);
        var expectedHeader = ResultExportWatermark.header("a@x.com", NOW, queryId);
        var expectedFooter = ResultExportWatermark.footer(1, 1);
        when(csvWriter.write(eq(columns), eq(List.of(List.of(1, "a"))), eq(expectedHeader),
                eq(expectedFooter))).thenReturn("csv".getBytes(StandardCharsets.UTF_8));
        when(signatureService.sign(any())).thenReturn("sig");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");

        var export = service.export(orgId, queryId, ComplianceReportFormat.CSV, submitterId,
                "a@x.com", false, null, null);

        assertThat(export.truncated()).isTrue();
        verify(csvWriter).write(eq(columns), eq(List.of(List.of(1, "a"))), eq(expectedHeader),
                eq(expectedFooter));
    }

    /**
     * When the format cap binds (policy cap larger), the watermark footer must carry the cap
     * that actually truncated the file — the format cap, not the policy's.
     */
    @Test
    void formatCapBindingOverLargerPolicyCapStampsTheEffectiveCap() {
        service = newService(new ComplianceProperties(null, 100, 2, 5));
        var capped = new ExportDecision(true, ExportPolicyMode.ROW_CAP, 100, true,
                List.of(UUID.randomUUID()), List.of());
        stubVisibleSelect("[[1,\"a\"],[2,\"b\"],[3,\"c\"]]", capped);
        var expectedHeader = ResultExportWatermark.header("a@x.com", NOW, queryId);
        var expectedFooter = ResultExportWatermark.footer(2, 2);
        when(csvWriter.write(eq(columns), eq(threeRows.subList(0, 2)), eq(expectedHeader),
                eq(expectedFooter))).thenReturn("csv".getBytes(StandardCharsets.UTF_8));
        when(signatureService.sign(any())).thenReturn("sig");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");

        var export = service.export(orgId, queryId, ComplianceReportFormat.CSV, submitterId,
                "a@x.com", false, null, null);

        assertThat(export.truncated()).isTrue();
        verify(csvWriter).write(eq(columns), eq(threeRows.subList(0, 2)), eq(expectedHeader),
                eq(expectedFooter));
    }

    /** An un-truncated WATERMARK export carries no cap suffix — nothing bound it. */
    @Test
    void uncappedWatermarkFooterCarriesNoCapSuffix() {
        var watermark = new ExportDecision(true, ExportPolicyMode.WATERMARK, null, true,
                List.of(UUID.randomUUID()), List.of());
        stubVisibleSelect("[[1,\"a\"]]", watermark);
        var expectedHeader = ResultExportWatermark.header("a@x.com", NOW, queryId);
        var expectedFooter = ResultExportWatermark.footer(1, null);
        when(csvWriter.write(eq(columns), eq(List.of(List.of(1, "a"))), eq(expectedHeader),
                eq(expectedFooter))).thenReturn("csv".getBytes(StandardCharsets.UTF_8));
        when(signatureService.sign(any())).thenReturn("sig");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");

        var export = service.export(orgId, queryId, ComplianceReportFormat.CSV, submitterId,
                "a@x.com", false, null, null);

        assertThat(export.truncated()).isFalse();
        verify(csvWriter).write(eq(columns), eq(List.of(List.of(1, "a"))), eq(expectedHeader),
                eq(expectedFooter));
    }

    @Test
    void storedTruncatedFlagCarriesThroughWithoutCapping() {
        when(querySnapshotService.find(queryId, orgId))
                .thenReturn(Optional.of(snapshot(QueryType.SELECT)));
        when(queryResultPersistenceService.find(queryId))
                .thenReturn(Optional.of(result("[[1,\"a\"]]", true)));
        when(governanceService.resultColumnNames(queryId)).thenReturn(columns);
        when(governanceService.decide(any(QuerySnapshotView.class), eq(columns),
                eq(submitterId))).thenReturn(allowAll());
        when(csvWriter.write(eq(columns), eq(List.of(List.of(1, "a"))), isNull(), isNull()))
                .thenReturn("csv".getBytes(StandardCharsets.UTF_8));
        when(signatureService.sign(any())).thenReturn("sig");
        when(signatureService.algorithm()).thenReturn("SHA256withRSA");

        var export = service.export(orgId, queryId, ComplianceReportFormat.CSV, submitterId,
                "a@x.com", false, null, null);

        assertThat(export.truncated()).isTrue();
    }

    // --- decisionFor ---------------------------------------------------------------------------

    @Test
    void decisionForThrowsNotFoundWhenNoSnapshot() {
        when(querySnapshotService.find(queryId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decisionFor(orgId, queryId, submitterId, false))
                .isInstanceOf(ResultExportNotFoundException.class);
    }

    @Test
    void decisionForThrowsNotFoundWhenNoResultRow() {
        when(querySnapshotService.find(queryId, orgId))
                .thenReturn(Optional.of(snapshot(QueryType.SELECT)));
        when(queryResultPersistenceService.find(queryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decisionFor(orgId, queryId, submitterId, false))
                .isInstanceOf(ResultExportNotFoundException.class);
    }

    @Test
    void decisionForThrowsUnavailableForNonSelect() {
        when(querySnapshotService.find(queryId, orgId))
                .thenReturn(Optional.of(snapshot(QueryType.DELETE)));

        assertThatThrownBy(() -> service.decisionFor(orgId, queryId, submitterId, false))
                .isInstanceOf(ResultExportUnavailableException.class);
    }

    @Test
    void decisionForReturnsGovernanceDecision() {
        var decision = new ExportDecision(true, ExportPolicyMode.WATERMARK, null, true,
                List.of(UUID.randomUUID()), List.of(DataClassification.PII));
        when(querySnapshotService.find(queryId, orgId))
                .thenReturn(Optional.of(snapshot(QueryType.SELECT)));
        when(queryResultPersistenceService.find(queryId))
                .thenReturn(Optional.of(result("[[1,\"a\"]]", false)));
        when(governanceService.resultColumnNames(queryId)).thenReturn(columns);
        when(governanceService.decide(any(QuerySnapshotView.class), eq(columns),
                eq(submitterId))).thenReturn(decision);

        assertThat(service.decisionFor(orgId, queryId, submitterId, false)).isEqualTo(decision);
    }
}
