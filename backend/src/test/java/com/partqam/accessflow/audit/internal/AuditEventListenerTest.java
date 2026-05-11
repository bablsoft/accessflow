package com.partqam.accessflow.audit.internal;

import com.partqam.accessflow.audit.api.AuditAction;
import com.partqam.accessflow.audit.api.AuditEntry;
import com.partqam.accessflow.audit.api.AuditLogService;
import com.partqam.accessflow.audit.api.AuditResourceType;
import com.partqam.accessflow.core.api.DatasourceConnectionDescriptor;
import com.partqam.accessflow.core.api.DatasourceLookupService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestSnapshot;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.events.AiAnalysisCompletedEvent;
import com.partqam.accessflow.core.events.AiAnalysisFailedEvent;
import com.partqam.accessflow.core.events.DatasourceDeactivatedEvent;
import com.partqam.accessflow.core.events.QueryAutoApprovedEvent;
import com.partqam.accessflow.core.events.QueryReadyForReviewEvent;
import com.partqam.accessflow.core.events.QueryTimedOutEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditEventListenerTest {

    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final QueryRequestLookupService queryRequestLookupService = mock(QueryRequestLookupService.class);
    private final DatasourceLookupService datasourceLookupService = mock(DatasourceLookupService.class);
    private final AuditEventListener listener = new AuditEventListener(
            auditLogService, queryRequestLookupService, datasourceLookupService);

    private final UUID organizationId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();

    private QueryRequestSnapshot snapshot(UUID queryId) {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", QueryType.SELECT, QueryStatus.PENDING_REVIEW);
    }

    @Test
    void onAiCompletedRecordsQueryAiAnalyzed() {
        var queryId = UUID.randomUUID();
        var aiAnalysisId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onAiCompleted(new AiAnalysisCompletedEvent(queryId, aiAnalysisId, RiskLevel.HIGH));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_AI_ANALYZED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
        assertThat(entry.resourceId()).isEqualTo(queryId);
        assertThat(entry.organizationId()).isEqualTo(organizationId);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.metadata()).containsEntry("risk_level", "HIGH");
        assertThat(entry.metadata()).containsEntry("ai_analysis_id", aiAnalysisId.toString());
    }

    @Test
    void onAiFailedRecordsQueryAiFailedWithReason() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onAiFailed(new AiAnalysisFailedEvent(queryId, "model-timeout"));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_AI_FAILED);
        assertThat(entry.metadata()).containsEntry("reason", "model-timeout");
    }

    @Test
    void onQueryReadyForReviewRecordsRow() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onQueryReadyForReview(new QueryReadyForReviewEvent(queryId));

        assertThat(captor.getValue().action()).isEqualTo(AuditAction.QUERY_REVIEW_REQUESTED);
        assertThat(captor.getValue().actorId()).isNull();
    }

    @Test
    void onQueryAutoApprovedRecordsApprovedWithMetadata() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onQueryAutoApproved(new QueryAutoApprovedEvent(queryId));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_APPROVED);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.metadata()).containsEntry("auto_approved", true);
    }

    @Test
    void onQueryTimedOutRecordsRejectedWithAutoMetadata() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onQueryTimedOut(new QueryTimedOutEvent(queryId, 24));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_REJECTED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
        assertThat(entry.resourceId()).isEqualTo(queryId);
        assertThat(entry.organizationId()).isEqualTo(organizationId);
        assertThat(entry.actorId()).isNull();
        assertThat(entry.metadata()).containsEntry("auto_rejected", true);
        assertThat(entry.metadata()).containsEntry("reason", "approval_timeout");
        assertThat(entry.metadata()).containsEntry("timeout_hours", 24);
    }

    @Test
    void onDatasourceDeactivatedRecordsUpdatedWithChange() {
        var descriptor = new DatasourceConnectionDescriptor(datasourceId, organizationId,
                DbType.POSTGRESQL, "h", 5432, "db", "u", "ENC", SslMode.DISABLE, 5, 1000,
                false, null, false);
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.of(descriptor));
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        when(auditLogService.record(captor.capture())).thenReturn(UUID.randomUUID());

        listener.onDatasourceDeactivated(new DatasourceDeactivatedEvent(datasourceId));

        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.DATASOURCE_UPDATED);
        assertThat(entry.resourceType()).isEqualTo(AuditResourceType.DATASOURCE);
        assertThat(entry.resourceId()).isEqualTo(datasourceId);
        assertThat(entry.organizationId()).isEqualTo(organizationId);
        assertThat(entry.metadata()).containsEntry("change", "deactivated");
    }

    @Test
    void unknownQueryIdSkipsAuditRow() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        listener.onAiCompleted(new AiAnalysisCompletedEvent(queryId, UUID.randomUUID(), RiskLevel.LOW));

        verify(auditLogService, never()).record(any());
    }

    @Test
    void runtimeFailureFromAuditServiceIsSwallowed() {
        var queryId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot(queryId)));
        when(auditLogService.record(any())).thenThrow(new RuntimeException("db down"));

        listener.onQueryReadyForReview(new QueryReadyForReviewEvent(queryId));
        // No exception should propagate.
    }

    @Test
    void datasourceDeactivatedSkipsWhenLookupReturnsEmpty() {
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.empty());

        listener.onDatasourceDeactivated(new DatasourceDeactivatedEvent(datasourceId));

        verify(auditLogService, never()).record(any());
    }
}
