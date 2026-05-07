package com.partqam.accessflow.workflow.internal;

import com.partqam.accessflow.audit.api.AuditAction;
import com.partqam.accessflow.audit.api.AuditEntry;
import com.partqam.accessflow.audit.api.AuditLogService;
import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestNotFoundException;
import com.partqam.accessflow.core.api.QueryRequestSnapshot;
import com.partqam.accessflow.core.api.QueryRequestStateService;
import com.partqam.accessflow.core.api.QueryResultPersistenceService;
import com.partqam.accessflow.core.api.QueryResultPersistenceService.SaveResultCommand;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RecordExecutionCommand;
import com.partqam.accessflow.proxy.api.QueryExecutor;
import com.partqam.accessflow.proxy.api.ResultColumn;
import com.partqam.accessflow.proxy.api.SelectExecutionResult;
import com.partqam.accessflow.proxy.api.UpdateExecutionResult;
import com.partqam.accessflow.workflow.api.QueryLifecycleService.CancelQueryCommand;
import com.partqam.accessflow.workflow.api.QueryLifecycleService.ExecuteQueryCommand;
import com.partqam.accessflow.workflow.api.QueryNotCancellableException;
import com.partqam.accessflow.workflow.api.QueryNotExecutableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryLifecycleServiceTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock QueryRequestStateService queryRequestStateService;
    @Mock QueryResultPersistenceService queryResultPersistenceService;
    @Mock QueryExecutor queryExecutor;
    @Mock AuditLogService auditLogService;
    @Mock MessageSource messageSource;

    DefaultQueryLifecycleService service;

    private final UUID queryId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultQueryLifecycleService(
                queryRequestLookupService,
                queryRequestStateService,
                queryResultPersistenceService,
                queryExecutor,
                auditLogService,
                new ObjectMapper(),
                messageSource);
    }

    private QueryRequestSnapshot snapshot(QueryStatus status, QueryType type) {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", type, status);
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void cancelTransitionsPendingAiToCancelledAndAudits() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_AI, QueryType.SELECT)));

        service.cancel(new CancelQueryCommand(queryId, submitterId, organizationId));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.CANCELLED);
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditAction.QUERY_CANCELLED);
        assertThat(captor.getValue().resourceId()).isEqualTo(queryId);
        assertThat(captor.getValue().actorId()).isEqualTo(submitterId);
        assertThat(captor.getValue().organizationId()).isEqualTo(organizationId);
    }

    @Test
    void cancelTransitionsPendingReviewToCancelled() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW, QueryType.SELECT)));

        service.cancel(new CancelQueryCommand(queryId, submitterId, organizationId));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_REVIEW,
                QueryStatus.CANCELLED);
    }

    @Test
    void cancelThrowsAccessDeniedWhenCallerIsNotSubmitter() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW, QueryType.SELECT)));
        when(messageSource.getMessage(eq("error.query_not_owned_by_caller"), any(), any(Locale.class)))
                .thenReturn("not yours");

        assertThatThrownBy(() -> service.cancel(
                new CancelQueryCommand(queryId, otherUserId, organizationId)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not yours");

        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
        verify(auditLogService, never()).record(any());
    }

    @Test
    void cancelThrowsNotCancellableWhenAlreadyExecuted() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.EXECUTED, QueryType.SELECT)));

        assertThatThrownBy(() -> service.cancel(
                new CancelQueryCommand(queryId, submitterId, organizationId)))
                .isInstanceOf(QueryNotCancellableException.class);

        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void cancelThrowsNotFoundWhenQueryMissing() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(
                new CancelQueryCommand(queryId, submitterId, organizationId)))
                .isInstanceOf(QueryRequestNotFoundException.class);
    }

    @Test
    void cancelThrowsNotFoundWhenQueryBelongsToDifferentOrg() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_AI, QueryType.SELECT)));

        assertThatThrownBy(() -> service.cancel(
                new CancelQueryCommand(queryId, submitterId, UUID.randomUUID())))
                .isInstanceOf(QueryRequestNotFoundException.class);
    }

    @Test
    void cancelSwallowsAuditFailureSilently() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_AI, QueryType.SELECT)));
        org.mockito.Mockito.doThrow(new RuntimeException("audit-down"))
                .when(auditLogService).record(any());

        // Should not propagate the audit failure to the caller.
        service.cancel(new CancelQueryCommand(queryId, submitterId, organizationId));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.CANCELLED);
    }

    // ── execute (success) ─────────────────────────────────────────────────────

    @Test
    void executeSelectPersistsResultsAndTransitionsToExecuted() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT)));
        when(queryExecutor.execute(any())).thenReturn(new SelectExecutionResult(
                List.of(new ResultColumn("id", 4, "int4"),
                        new ResultColumn("name", 12, "text")),
                List.of(List.of(1, "alice"), List.of(2, "bob")),
                2L, false, Duration.ofMillis(123)));

        var outcome = service.execute(new ExecuteQueryCommand(queryId, submitterId,
                organizationId, false));

        assertThat(outcome.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(outcome.rowsAffected()).isEqualTo(2L);
        assertThat(outcome.durationMs()).isEqualTo(123);

        var saveCaptor = ArgumentCaptor.forClass(SaveResultCommand.class);
        verify(queryResultPersistenceService).save(saveCaptor.capture());
        var saved = saveCaptor.getValue();
        assertThat(saved.queryRequestId()).isEqualTo(queryId);
        assertThat(saved.rowCount()).isEqualTo(2L);
        assertThat(saved.truncated()).isFalse();
        assertThat(saved.durationMs()).isEqualTo(123);
        assertThat(saved.columnsJson()).contains("\"id\"").contains("\"int4\"");
        assertThat(saved.rowsJson()).contains("\"alice\"").contains("\"bob\"");

        var execCaptor = ArgumentCaptor.forClass(RecordExecutionCommand.class);
        verify(queryRequestStateService).recordExecutionOutcome(execCaptor.capture());
        assertThat(execCaptor.getValue().outcome()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(execCaptor.getValue().rowsAffected()).isEqualTo(2L);
        assertThat(execCaptor.getValue().errorMessage()).isNull();

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.QUERY_EXECUTED);
    }

    @Test
    void executeUpdateDoesNotPersistResultRows() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.UPDATE)));
        when(queryExecutor.execute(any()))
                .thenReturn(new UpdateExecutionResult(7L, Duration.ofMillis(40)));

        var outcome = service.execute(new ExecuteQueryCommand(queryId, submitterId,
                organizationId, false));

        assertThat(outcome.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(outcome.rowsAffected()).isEqualTo(7L);
        verify(queryResultPersistenceService, never()).save(any());
    }

    @Test
    void executeAllowsAdminWhoIsNotSubmitter() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.UPDATE)));
        when(queryExecutor.execute(any()))
                .thenReturn(new UpdateExecutionResult(1L, Duration.ofMillis(5)));

        var outcome = service.execute(new ExecuteQueryCommand(queryId, otherUserId,
                organizationId, true));

        assertThat(outcome.status()).isEqualTo(QueryStatus.EXECUTED);
    }

    // ── execute (rejection) ───────────────────────────────────────────────────

    @Test
    void executeThrowsAccessDeniedForNonAdminNonSubmitter() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT)));
        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenReturn("forbidden");

        assertThatThrownBy(() -> service.execute(
                new ExecuteQueryCommand(queryId, otherUserId, organizationId, false)))
                .isInstanceOf(AccessDeniedException.class);

        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void executeThrowsNotExecutableWhenStatusIsNotApproved() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW, QueryType.SELECT)));

        assertThatThrownBy(() -> service.execute(
                new ExecuteQueryCommand(queryId, submitterId, organizationId, false)))
                .isInstanceOf(QueryNotExecutableException.class);

        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void executeThrowsNotFoundWhenQueryMissing() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(
                new ExecuteQueryCommand(queryId, submitterId, organizationId, false)))
                .isInstanceOf(QueryRequestNotFoundException.class);
    }

    // ── execute (failure) ─────────────────────────────────────────────────────

    @Test
    void executeRecordsFailureWhenExecutorThrows() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.UPDATE)));
        when(queryExecutor.execute(any()))
                .thenThrow(new RuntimeException("connection refused"));

        var outcome = service.execute(new ExecuteQueryCommand(queryId, submitterId,
                organizationId, false));

        assertThat(outcome.status()).isEqualTo(QueryStatus.FAILED);
        assertThat(outcome.rowsAffected()).isNull();

        var execCaptor = ArgumentCaptor.forClass(RecordExecutionCommand.class);
        verify(queryRequestStateService).recordExecutionOutcome(execCaptor.capture());
        assertThat(execCaptor.getValue().outcome()).isEqualTo(QueryStatus.FAILED);
        assertThat(execCaptor.getValue().errorMessage()).isEqualTo("connection refused");
        verify(queryResultPersistenceService, never()).save(any());

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.QUERY_FAILED);
        assertThat(auditCaptor.getValue().metadata()).containsEntry("error", "connection refused");
    }

    @Test
    void executeRecordsFailureUsingClassNameWhenExceptionMessageIsNull() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.UPDATE)));
        when(queryExecutor.execute(any())).thenThrow(new RuntimeException());

        var outcome = service.execute(new ExecuteQueryCommand(queryId, submitterId,
                organizationId, false));

        assertThat(outcome.status()).isEqualTo(QueryStatus.FAILED);
        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().metadata()).containsEntry("error", "RuntimeException");
    }
}
