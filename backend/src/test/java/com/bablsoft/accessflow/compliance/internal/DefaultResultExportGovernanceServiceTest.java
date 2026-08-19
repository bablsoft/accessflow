package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.compliance.api.ExportDecision;
import com.bablsoft.accessflow.compliance.events.SensitiveResultExportedEvent;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationQueryService;
import com.bablsoft.accessflow.core.api.DataClassificationTagView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;
import com.bablsoft.accessflow.core.api.ExportPolicyResolutionService;
import com.bablsoft.accessflow.core.api.ExportPolicyView;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultResultExportGovernanceServiceTest {

    @Mock ExportPolicyResolutionService exportPolicyResolutionService;
    @Mock DataClassificationQueryService dataClassificationQueryService;
    @Mock QuerySnapshotService querySnapshotService;
    @Mock QueryResultPersistenceService queryResultPersistenceService;
    @Mock AuditLogService auditLogService;
    @Mock ApplicationEventPublisher eventPublisher;

    private DefaultResultExportGovernanceService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID queryId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant t = Instant.parse("2026-07-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        service = new DefaultResultExportGovernanceService(exportPolicyResolutionService,
                dataClassificationQueryService, querySnapshotService,
                queryResultPersistenceService, auditLogService, eventPublisher,
                new ObjectMapper());
    }

    private QuerySnapshotView snapshot(List<String> referencedTables) {
        return new QuerySnapshotView(UUID.randomUUID(), queryId, orgId, datasourceId, userId,
                "SELECT * FROM customers", QueryType.SELECT, false, DbType.POSTGRESQL,
                referencedTables, "hash", null, null, 5L, 10, t, t);
    }

    private DataClassificationTagView tag(String table, String column, DataClassification c) {
        return new DataClassificationTagView(UUID.randomUUID(), datasourceId, table, column, c,
                null, t, t);
    }

    private ExportPolicyView policy(ExportPolicyMode mode, Integer rowCap,
                                    List<DataClassification> deny) {
        return new ExportPolicyView(UUID.randomUUID(), datasourceId, mode, rowCap, deny,
                List.of(), List.of(), List.of(), true, t, t);
    }

    private ExportDecision decision(List<UUID> policyIds, List<DataClassification> present,
                                    boolean watermark) {
        return new ExportDecision(true, watermark ? ExportPolicyMode.WATERMARK
                : ExportPolicyMode.ALLOW, null, watermark, policyIds, present);
    }

    // --- decide(orgId, queryRequestId, userId) -------------------------------------------------

    /**
     * Fail closed: without a snapshot neither policies nor classifications are resolvable, so
     * the decision must deny (the email-attachment caller then suppresses the attachment).
     */
    @Test
    void decideFailsClosedWhenNoSnapshot() {
        when(querySnapshotService.find(queryId, orgId)).thenReturn(Optional.empty());

        var decision = service.decide(orgId, queryId, userId);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.rowCap()).isNull();
        assertThat(decision.watermark()).isFalse();
        assertThat(decision.policyIds()).isEmpty();
        assertThat(decision.classificationsPresent()).isEmpty();
        verifyNoInteractions(exportPolicyResolutionService, dataClassificationQueryService,
                queryResultPersistenceService);
    }

    @Test
    void decideFullPathParsesColumnsAndCombinesPolicies() {
        when(querySnapshotService.find(queryId, orgId))
                .thenReturn(Optional.of(snapshot(List.of("public.customers"))));
        when(queryResultPersistenceService.find(queryId)).thenReturn(Optional.of(
                new QueryResultPersistenceService.QueryResultSnapshot(queryId,
                        "[{\"name\":\"id\",\"type\":\"int4\",\"restricted\":false},"
                                + "{\"name\":\"ssn\",\"type\":\"text\",\"restricted\":false}]",
                        "[]", 0, false, null, 1)));
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of(tag("customers", "SSN", DataClassification.PII)));
        var watermark = policy(ExportPolicyMode.WATERMARK, null, List.of());
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of(watermark));

        var decision = service.decide(orgId, queryId, userId);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.effectiveMode()).isEqualTo(ExportPolicyMode.WATERMARK);
        assertThat(decision.watermark()).isTrue();
        assertThat(decision.policyIds()).containsExactly(watermark.id());
        assertThat(decision.classificationsPresent()).containsExactly(DataClassification.PII);
    }

    // --- combine ordering ----------------------------------------------------------------------

    @Test
    void watermarkBeatsAllow() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of());
        var allow = policy(ExportPolicyMode.ALLOW, null, List.of());
        var watermark = policy(ExportPolicyMode.WATERMARK, null, List.of());
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of(allow, watermark));

        var decision = service.decide(snapshot(List.of("customers")), List.of("id"), userId);

        assertThat(decision.effectiveMode()).isEqualTo(ExportPolicyMode.WATERMARK);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.watermark()).isTrue();
        assertThat(decision.rowCap()).isNull();
        assertThat(decision.policyIds()).containsExactly(allow.id(), watermark.id());
    }

    @Test
    void rowCapBeatsWatermark() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of());
        var watermark = policy(ExportPolicyMode.WATERMARK, null, List.of());
        var rowCap = policy(ExportPolicyMode.ROW_CAP, 10, List.of());
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of(watermark, rowCap));

        var decision = service.decide(snapshot(List.of("customers")), List.of("id"), userId);

        assertThat(decision.effectiveMode()).isEqualTo(ExportPolicyMode.ROW_CAP);
        assertThat(decision.rowCap()).isEqualTo(10);
        assertThat(decision.watermark()).isTrue();
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void minimumRowCapWinsAcrossRowCapPolicies() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of());
        var big = policy(ExportPolicyMode.ROW_CAP, 100, List.of());
        var small = policy(ExportPolicyMode.ROW_CAP, 50, List.of());
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of(big, small));

        var decision = service.decide(snapshot(List.of("customers")), List.of("id"), userId);

        assertThat(decision.rowCap()).isEqualTo(50);
        assertThat(decision.effectiveMode()).isEqualTo(ExportPolicyMode.ROW_CAP);
    }

    @Test
    void allowOnlyPolicyIsUnwatermarked() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of());
        var allow = policy(ExportPolicyMode.ALLOW, null, List.of());
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of(allow));

        var decision = service.decide(snapshot(List.of("customers")), List.of("id"), userId);

        assertThat(decision.effectiveMode()).isEqualTo(ExportPolicyMode.ALLOW);
        assertThat(decision.watermark()).isFalse();
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.policyIds()).containsExactly(allow.id());
    }

    // --- DENY_CLASSIFIED participation ---------------------------------------------------------

    @Test
    void denyClassifiedWinsWhenClassificationMatches() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of(tag("customers", "ssn", DataClassification.PII)));
        var rowCap = policy(ExportPolicyMode.ROW_CAP, 10, List.of());
        var deny = policy(ExportPolicyMode.DENY_CLASSIFIED, null,
                List.of(DataClassification.PII));
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of(rowCap, deny));

        var decision = service.decide(snapshot(List.of("customers")), List.of("ssn"), userId);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.effectiveMode()).isEqualTo(ExportPolicyMode.DENY_CLASSIFIED);
        assertThat(decision.rowCap()).isNull();
        assertThat(decision.watermark()).isFalse();
        assertThat(decision.policyIds()).containsExactly(rowCap.id(), deny.id());
        assertThat(decision.classificationsPresent()).containsExactly(DataClassification.PII);
    }

    @Test
    void denyWithEmptyListDeniesOnAnyClassification() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of(tag("customers", "ssn", DataClassification.FINANCIAL)));
        var deny = policy(ExportPolicyMode.DENY_CLASSIFIED, null, List.of());
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of(deny));

        var decision = service.decide(snapshot(List.of("customers")), List.of("ssn"), userId);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.effectiveMode()).isEqualTo(ExportPolicyMode.DENY_CLASSIFIED);
    }

    @Test
    void denyDropsOutWhenNoClassificationsPresent() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of());
        var allow = policy(ExportPolicyMode.ALLOW, null, List.of());
        var deny = policy(ExportPolicyMode.DENY_CLASSIFIED, null, List.of());
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of(allow, deny));

        var decision = service.decide(snapshot(List.of("customers")), List.of("id"), userId);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.effectiveMode()).isEqualTo(ExportPolicyMode.ALLOW);
        assertThat(decision.policyIds()).containsExactly(allow.id());
    }

    @Test
    void denyDropsOutWhenClassificationsAreDisjoint() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of(tag("customers", "ssn", DataClassification.PII)));
        var deny = policy(ExportPolicyMode.DENY_CLASSIFIED, null,
                List.of(DataClassification.PCI));
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of(deny));

        var decision = service.decide(snapshot(List.of("customers")), List.of("ssn"), userId);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.effectiveMode()).isEqualTo(ExportPolicyMode.ALLOW);
        assertThat(decision.policyIds()).isEmpty();
        assertThat(decision.classificationsPresent()).containsExactly(DataClassification.PII);
    }

    // --- classification matching ---------------------------------------------------------------

    @Test
    void tableLevelTagClassifiesAllResultColumns() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of(
                        tag("customers", null, DataClassification.GDPR),
                        tag("customers", "  ", DataClassification.PHI)));
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of());

        var decision = service.decide(snapshot(List.of("customers")),
                List.of("some_other_column"), userId);

        // EnumSet ordering: PHI precedes GDPR in the DataClassification declaration.
        assertThat(decision.classificationsPresent())
                .containsExactly(DataClassification.PHI, DataClassification.GDPR);
    }

    @Test
    void columnLevelTagMatchesCaseInsensitively() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of(
                        tag("customers", "SSN", DataClassification.PII),
                        tag("customers", "card_number", DataClassification.PCI)));
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of());

        var decision = service.decide(snapshot(List.of("customers")), List.of("Ssn", "email"),
                userId);

        assertThat(decision.classificationsPresent()).containsExactly(DataClassification.PII);
    }

    @Test
    void bareTagTableMatchesSchemaQualifiedReferencedTable() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of(tag("CUSTOMERS", "ssn", DataClassification.PII)));
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of());

        var decision = service.decide(snapshot(List.of("public.customers")), List.of("ssn"),
                userId);

        assertThat(decision.classificationsPresent()).containsExactly(DataClassification.PII);
    }

    @Test
    void qualifiedTagInDifferentSchemaDoesNotMatch() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of(tag("other.customers", "ssn", DataClassification.PII)));
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of());

        var decision = service.decide(snapshot(List.of("public.customers")), List.of("ssn"),
                userId);

        assertThat(decision.classificationsPresent()).isEmpty();
    }

    @Test
    void noTagsMeansNoClassifications() {
        when(dataClassificationQueryService.findByDatasource(datasourceId, orgId))
                .thenReturn(List.of());
        when(exportPolicyResolutionService.resolveApplicable(orgId, datasourceId, userId))
                .thenReturn(List.of());

        var decision = service.decide(snapshot(List.of("customers")), List.of("ssn"), userId);

        assertThat(decision.classificationsPresent()).isEmpty();
    }

    // --- resultColumnNames ---------------------------------------------------------------------

    @Test
    void resultColumnNamesParsesColumnsJson() {
        when(queryResultPersistenceService.find(queryId)).thenReturn(Optional.of(
                new QueryResultPersistenceService.QueryResultSnapshot(queryId,
                        "[{\"name\":\"id\",\"type\":\"int4\",\"restricted\":false},"
                                + "{\"name\":\"email\",\"type\":\"text\",\"restricted\":true},"
                                + "{\"type\":\"text\"}]",
                        "[]", 0, false, null, 1)));

        assertThat(service.resultColumnNames(queryId)).containsExactly("id", "email");
    }

    @Test
    void resultColumnNamesEmptyWhenNoResultRow() {
        when(queryResultPersistenceService.find(queryId)).thenReturn(Optional.empty());

        assertThat(service.resultColumnNames(queryId)).isEmpty();
    }

    @Test
    void resultColumnNamesEmptyWhenColumnsJsonNull() {
        when(queryResultPersistenceService.find(queryId)).thenReturn(Optional.of(
                new QueryResultPersistenceService.QueryResultSnapshot(queryId, null, "[]", 0,
                        false, null, 1)));

        assertThat(service.resultColumnNames(queryId)).isEmpty();
    }

    @Test
    void resultColumnNamesToleratesGarbageJson() {
        when(queryResultPersistenceService.find(queryId)).thenReturn(Optional.of(
                new QueryResultPersistenceService.QueryResultSnapshot(queryId, "not json at all",
                        "[]", 0, false, null, 1)));

        assertThat(service.resultColumnNames(queryId)).isEmpty();
    }

    // --- recordAttachmentExport / publishIfSensitive -------------------------------------------

    @Test
    void recordAttachmentExportWritesAuditAndPublishesWhenClassified() {
        when(querySnapshotService.find(queryId, orgId))
                .thenReturn(Optional.of(snapshot(List.of("customers"))));
        var policyId = UUID.randomUUID();
        var decision = new ExportDecision(true, ExportPolicyMode.WATERMARK, null, true,
                List.of(policyId), List.of(DataClassification.PII));

        service.recordAttachmentExport(orgId, queryId, userId, "rcpt@example.com", decision, 7,
                true);

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        var entry = auditCaptor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.RESULT_EXPORTED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
        assertThat(entry.resourceId()).isEqualTo(queryId);
        assertThat(entry.organizationId()).isEqualTo(orgId);
        assertThat(entry.actorId()).isEqualTo(userId);
        assertThat(entry.metadata())
                .containsEntry("trigger", "email_attachment")
                .containsEntry("format", "CSV")
                .containsEntry("row_count", 7L)
                .containsEntry("truncated", true)
                .containsEntry("watermarked", true)
                .containsEntry("policy_ids", List.of(policyId.toString()))
                .containsEntry("classifications_present", List.of("PII"));

        var eventCaptor = ArgumentCaptor.forClass(SensitiveResultExportedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        var event = eventCaptor.getValue();
        assertThat(event.organizationId()).isEqualTo(orgId);
        assertThat(event.queryRequestId()).isEqualTo(queryId);
        assertThat(event.datasourceId()).isEqualTo(datasourceId);
        assertThat(event.exporterUserId()).isEqualTo(userId);
        assertThat(event.exporterEmail()).isEqualTo("rcpt@example.com");
        assertThat(event.format()).isEqualTo("CSV");
        assertThat(event.rowCount()).isEqualTo(7);
        assertThat(event.watermarked()).isTrue();
        assertThat(event.classifications()).containsExactly(DataClassification.PII);
        assertThat(event.trigger()).isEqualTo("email_attachment");
    }

    @Test
    void recordAttachmentExportSwallowsAuditFailure() {
        doThrow(new RuntimeException("audit down")).when(auditLogService).record(any());
        var decision = decision(List.of(), List.of(), false);

        assertThatCode(() -> service.recordAttachmentExport(orgId, queryId, userId,
                "rcpt@example.com", decision, 1, false))
                .doesNotThrowAnyException();
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void recordAttachmentExportDoesNotPublishWhenUnclassified() {
        var decision = decision(List.of(), List.of(), false);

        service.recordAttachmentExport(orgId, queryId, userId, "rcpt@example.com", decision, 1,
                false);

        verify(auditLogService).record(any(AuditEntry.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void publishIfSensitiveNoOpsWhenClassificationsEmpty() {
        service.publishIfSensitive(orgId, queryId, userId, "a@x.com", "CSV", 1,
                decision(List.of(), List.of(), false), "endpoint");

        verifyNoInteractions(eventPublisher, querySnapshotService);
    }

    @Test
    void publishIfSensitiveCarriesNullDatasourceWhenSnapshotMissing() {
        when(querySnapshotService.find(queryId, orgId)).thenReturn(Optional.empty());

        service.publishIfSensitive(orgId, queryId, userId, "a@x.com", "PDF", 2,
                decision(List.of(), List.of(DataClassification.PCI), true), "endpoint");

        var captor = ArgumentCaptor.forClass(SensitiveResultExportedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().datasourceId()).isNull();
        assertThat(captor.getValue().format()).isEqualTo("PDF");
        assertThat(captor.getValue().trigger()).isEqualTo("endpoint");
    }
}
