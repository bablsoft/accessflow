package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.AiAnalysisSummaryView;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService.SaveResultCommand;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RecordExecutionCommand;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SqlCanonicalizer;
import com.bablsoft.accessflow.core.api.ResolvedColumnMask;
import com.bablsoft.accessflow.core.events.AiReanalysisRequestedEvent;
import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService.CancelQueryCommand;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService.ExecuteQueryCommand;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService.ReanalyzeQueryCommand;
import com.bablsoft.accessflow.workflow.api.QueryNotCancellableException;
import com.bablsoft.accessflow.workflow.api.QueryNotExecutableException;
import com.bablsoft.accessflow.workflow.api.QueryNotReanalyzableException;
import com.bablsoft.accessflow.workflow.events.QueryCancelledEvent;
import com.bablsoft.accessflow.workflow.events.QueryExecutedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
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
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultQueryLifecycleServiceTest {

    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock com.bablsoft.accessflow.core.api.QueryRequestPersistenceService queryRequestPersistenceService;
    @Mock DatasourcePermissionVerifier permissionVerifier;
    @Mock com.bablsoft.accessflow.core.api.UserQueryService userQueryService;
    @Mock com.bablsoft.accessflow.core.api.RolePermissionResolver rolePermissionResolver;
    @Mock QueryRequestStateService queryRequestStateService;
    @Mock QueryResultPersistenceService queryResultPersistenceService;
    @Mock QueryExecutor queryExecutor;
    @Mock QueryParser queryParser;
    @Mock DatasourceLookupService datasourceLookupService;
    @Mock SqlCanonicalizer sqlCanonicalizer;
    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock MaskingPolicyResolutionService maskingPolicyResolutionService;
    @Mock RowSecurityResolutionService rowSecurityResolutionService;
    @Mock com.bablsoft.accessflow.lifecycle.api.LifecycleDirectiveResolutionService lifecycleDirectiveResolutionService;
    @Mock AiAnalysisLookupService aiAnalysisLookupService;
    @Mock AiAnalysisPersistenceService aiAnalysisPersistenceService;
    @Mock AuditLogService auditLogService;
    @Mock MessageSource messageSource;
    @Mock ApplicationEventPublisher eventPublisher;

    DefaultQueryLifecycleService service;

    private final UUID queryId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final java.time.Instant now = java.time.Instant.parse("2026-08-11T12:00:00Z");
    private final java.time.Clock fixedClock =
            java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new DefaultQueryLifecycleService(
                queryRequestLookupService,
                queryRequestPersistenceService,
                permissionVerifier,
                userQueryService,
                rolePermissionResolver,
                fixedClock,
                queryRequestStateService,
                queryResultPersistenceService,
                queryExecutor,
                queryParser,
                datasourceLookupService,
                sqlCanonicalizer,
                permissionLookupService,
                maskingPolicyResolutionService,
                rowSecurityResolutionService,
                lifecycleDirectiveResolutionService,
                aiAnalysisLookupService,
                aiAnalysisPersistenceService,
                auditLogService,
                new ObjectMapper(),
                messageSource,
                eventPublisher);
        when(queryParser.parse(anyString(), any())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return new SqlParseResult(QueryType.SELECT, sql);
        });
        when(sqlCanonicalizer.canonicalize(anyString())).thenAnswer(inv ->
                ((String) inv.getArgument(0)).toUpperCase(Locale.ROOT));
        when(maskingPolicyResolutionService.resolveApplicable(any(), any(), any()))
                .thenReturn(List.of());
    }

    private QueryRequestSnapshot snapshot(QueryStatus status, QueryType type) {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", type, false, status, null, null, null, false);
    }

    private QueryRequestSnapshot snapshot(QueryStatus status, QueryType type,
                                          java.time.Instant scheduledFor) {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", type, false, status, scheduledFor, null, null, false);
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void cancelTransitionsPendingAiToCancelledAndPublishesEvent() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_AI, QueryType.SELECT)));

        service.cancel(new CancelQueryCommand(queryId, submitterId, organizationId));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.CANCELLED);
        var captor = ArgumentCaptor.forClass(QueryCancelledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().queryRequestId()).isEqualTo(queryId);
        assertThat(captor.getValue().submitterUserId()).isEqualTo(submitterId);
        // Audit row is written from the controller (to capture IP/UA), not the service.
        verify(auditLogService, never()).record(any());
    }

    @Test
    void cancelTransitionsPendingReviewToCancelled() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW, QueryType.SELECT)));

        service.cancel(new CancelQueryCommand(queryId, submitterId, organizationId));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_REVIEW,
                QueryStatus.CANCELLED);
        verify(eventPublisher).publishEvent(any(QueryCancelledEvent.class));
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
        verify(eventPublisher, never()).publishEvent(any(QueryCancelledEvent.class));
    }

    @Test
    void cancelThrowsNotCancellableWhenAlreadyExecuted() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.EXECUTED, QueryType.SELECT)));

        assertThatThrownBy(() -> service.cancel(
                new CancelQueryCommand(queryId, submitterId, organizationId)))
                .isInstanceOf(QueryNotCancellableException.class);

        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(QueryCancelledEvent.class));
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
        assertThat(execCaptor.getValue().canonicalSql()).isEqualTo("SELECT 1");
        assertThat(execCaptor.getValue().previousRunId()).isNull();

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.QUERY_EXECUTED);
    }

    @Test
    void executeLinksPreviousRunWhenLookupServiceFindsMatch() {
        var prior = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT)));
        when(queryExecutor.execute(any())).thenReturn(new SelectExecutionResult(
                List.of(new ResultColumn("id", 4, "int4")),
                List.of(List.of(1)),
                1L, false, Duration.ofMillis(7)));
        when(queryRequestLookupService.findPreviousRunId(submitterId, datasourceId,
                "SELECT 1", queryId)).thenReturn(Optional.of(prior));

        service.execute(new ExecuteQueryCommand(queryId, submitterId, organizationId, false));

        var execCaptor = ArgumentCaptor.forClass(RecordExecutionCommand.class);
        verify(queryRequestStateService).recordExecutionOutcome(execCaptor.capture());
        assertThat(execCaptor.getValue().canonicalSql()).isEqualTo("SELECT 1");
        assertThat(execCaptor.getValue().previousRunId()).isEqualTo(prior);
    }

    @Test
    void executePassesRestrictedColumnsFromPermissionToExecutor() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT)));
        var permissionView = new DatasourceUserPermissionView(
                UUID.randomUUID(), submitterId, datasourceId, true, false, false, false,
                List.of(), List.of(), List.of("public.users.ssn", "public.users.email"), null);
        when(permissionLookupService.findFor(submitterId, datasourceId))
                .thenReturn(Optional.of(permissionView));
        when(queryExecutor.execute(any())).thenReturn(new SelectExecutionResult(
                List.of(new ResultColumn("id", 4, "int4", false),
                        new ResultColumn("ssn", 12, "varchar", true)),
                List.of(List.of(1, "***")),
                1L, false, Duration.ofMillis(50)));

        service.execute(new ExecuteQueryCommand(queryId, submitterId, organizationId, false));

        var requestCaptor = ArgumentCaptor.forClass(QueryExecutionRequest.class);
        verify(queryExecutor).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().restrictedColumns())
                .containsExactly("public.users.ssn", "public.users.email");

        var saveCaptor = ArgumentCaptor.forClass(SaveResultCommand.class);
        verify(queryResultPersistenceService).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().columnsJson())
                .contains("\"name\":\"ssn\"")
                .contains("\"restricted\":true")
                .contains("\"restricted\":false");
    }

    @Test
    void executeResolvesMaskingPoliciesAndAuditsAppliedIds() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT)));
        when(permissionLookupService.findFor(submitterId, datasourceId))
                .thenReturn(Optional.empty());
        var policyId = UUID.randomUUID();
        when(maskingPolicyResolutionService.resolveApplicable(organizationId, datasourceId,
                submitterId)).thenReturn(List.of(new ResolvedColumnMask(policyId,
                "public.users.email", MaskingStrategy.PARTIAL, java.util.Map.of("visible_suffix", "4"))));
        when(queryExecutor.execute(any())).thenReturn(new SelectExecutionResult(
                List.of(new ResultColumn("email", 12, "varchar", true)),
                List.of(List.of("****le.com")),
                1L, false, Duration.ofMillis(30), java.util.Set.of(policyId)));

        service.execute(new ExecuteQueryCommand(queryId, submitterId, organizationId, false));

        var requestCaptor = ArgumentCaptor.forClass(QueryExecutionRequest.class);
        verify(queryExecutor).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().columnMasks())
                .extracting(ColumnMaskDirective::strategy)
                .containsExactly(MaskingStrategy.PARTIAL);
        assertThat(requestCaptor.getValue().columnMasks())
                .extracting(ColumnMaskDirective::policyId)
                .containsExactly(policyId);

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        var executed = auditCaptor.getAllValues().stream()
                .filter(e -> e.action() == AuditAction.QUERY_EXECUTED)
                .findFirst().orElseThrow();
        assertThat(executed.metadata()).containsEntry("applied_masking_policy_ids",
                List.of(policyId.toString()));
    }

    @Test
    void executePassesEmptyRestrictedColumnsWhenNoPermissionFound() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT)));
        when(permissionLookupService.findFor(submitterId, datasourceId))
                .thenReturn(Optional.empty());
        when(queryExecutor.execute(any())).thenReturn(new SelectExecutionResult(
                List.of(new ResultColumn("id", 4, "int4")),
                List.of(List.of(1)),
                1L, false, Duration.ofMillis(20)));

        service.execute(new ExecuteQueryCommand(queryId, submitterId, organizationId, false));

        var requestCaptor = ArgumentCaptor.forClass(QueryExecutionRequest.class);
        verify(queryExecutor).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().restrictedColumns()).isEmpty();
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
        assertThat(execCaptor.getValue().canonicalSql()).isNull();
        assertThat(execCaptor.getValue().previousRunId()).isNull();
        verify(queryResultPersistenceService, never()).save(any());

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.QUERY_FAILED);
        assertThat(auditCaptor.getValue().metadata())
                .containsEntry("error", "connection refused")
                .doesNotContainKey("sql_state")
                .doesNotContainKey("vendor_code");
    }

    @Test
    void executeCapturesSqlStateAndVendorCodeWhenExecutionFailedExceptionThrown() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT)));
        when(queryExecutor.execute(any())).thenThrow(new QueryExecutionFailedException(
                "Query execution failed",
                "relation \"does_not_exist\" does not exist", "42P01", 0,
                new RuntimeException("cause")));

        var outcome = service.execute(new ExecuteQueryCommand(queryId, submitterId,
                organizationId, false));

        assertThat(outcome.status()).isEqualTo(QueryStatus.FAILED);

        // The verbatim driver detail — not the generic localized summary — is persisted so the
        // detail page can surface the real cause (AF-408).
        var execCaptor = ArgumentCaptor.forClass(RecordExecutionCommand.class);
        verify(queryRequestStateService).recordExecutionOutcome(execCaptor.capture());
        assertThat(execCaptor.getValue().errorMessage())
                .isEqualTo("relation \"does_not_exist\" does not exist");

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.QUERY_FAILED);
        assertThat(auditCaptor.getValue().metadata())
                .containsEntry("error", "relation \"does_not_exist\" does not exist")
                .containsEntry("sql_state", "42P01")
                .containsEntry("vendor_code", 0);
    }

    @Test
    void executeOmitsSqlStateWhenExecutionFailedExceptionHasNoSqlState() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT)));
        when(queryExecutor.execute(any())).thenThrow(new QueryExecutionFailedException(
                "Query execution failed", "driver returned no sql state", null, 0,
                new RuntimeException("cause")));

        service.execute(new ExecuteQueryCommand(queryId, submitterId, organizationId, false));

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().metadata())
                .containsEntry("error", "driver returned no sql state")
                .doesNotContainKey("sql_state")
                .doesNotContainKey("vendor_code");
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

    // ── reanalyze ─────────────────────────────────────────────────────────────

    private AiAnalysisSummaryView failedAnalysis() {
        return new AiAnalysisSummaryView(UUID.randomUUID(), queryId, RiskLevel.CRITICAL, 100,
                "AI analysis failed: provider unavailable", true, "provider unavailable");
    }

    private AiAnalysisSummaryView succeededAnalysis() {
        return new AiAnalysisSummaryView(UUID.randomUUID(), queryId, RiskLevel.LOW, 10,
                "ok", false, null);
    }

    @Test
    void reanalyzeDeletesStaleRowAndPublishesEvent() {
        var callerId = UUID.randomUUID();
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW, QueryType.SELECT)));
        when(aiAnalysisLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(failedAnalysis()));

        service.reanalyze(new ReanalyzeQueryCommand(queryId, callerId, organizationId));

        verify(aiAnalysisPersistenceService).deleteForQuery(queryId);
        var captor = ArgumentCaptor.forClass(AiReanalysisRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().queryRequestId()).isEqualTo(queryId);
        assertThat(captor.getValue().requestedByUserId()).isEqualTo(callerId);
    }

    @Test
    void reanalyzeThrowsWhenQueryStatusIsNotPendingReview() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT)));

        assertThatThrownBy(() -> service.reanalyze(
                new ReanalyzeQueryCommand(queryId, UUID.randomUUID(), organizationId)))
                .isInstanceOf(QueryNotReanalyzableException.class);

        verify(aiAnalysisPersistenceService, never()).deleteForQuery(any());
        verify(eventPublisher, never()).publishEvent(any(AiReanalysisRequestedEvent.class));
    }

    @Test
    void reanalyzeThrowsWhenPreviousAnalysisDidNotFail() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW, QueryType.SELECT)));
        when(aiAnalysisLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(succeededAnalysis()));

        assertThatThrownBy(() -> service.reanalyze(
                new ReanalyzeQueryCommand(queryId, UUID.randomUUID(), organizationId)))
                .isInstanceOf(QueryNotReanalyzableException.class);

        verify(aiAnalysisPersistenceService, never()).deleteForQuery(any());
        verify(eventPublisher, never()).publishEvent(any(AiReanalysisRequestedEvent.class));
    }

    @Test
    void reanalyzeThrowsWhenNoAnalysisExists() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW, QueryType.SELECT)));
        when(aiAnalysisLookupService.findByQueryRequestId(queryId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reanalyze(
                new ReanalyzeQueryCommand(queryId, UUID.randomUUID(), organizationId)))
                .isInstanceOf(QueryNotReanalyzableException.class);

        verify(aiAnalysisPersistenceService, never()).deleteForQuery(any());
        verify(eventPublisher, never()).publishEvent(any(AiReanalysisRequestedEvent.class));
    }

    @Test
    void reanalyzeThrowsNotFoundWhenQueryMissing() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reanalyze(
                new ReanalyzeQueryCommand(queryId, UUID.randomUUID(), organizationId)))
                .isInstanceOf(QueryRequestNotFoundException.class);
    }

    @Test
    void reanalyzeThrowsNotFoundWhenQueryBelongsToDifferentOrg() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW, QueryType.SELECT)));

        assertThatThrownBy(() -> service.reanalyze(
                new ReanalyzeQueryCommand(queryId, UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(QueryRequestNotFoundException.class);

        verify(eventPublisher, never()).publishEvent(any(AiReanalysisRequestedEvent.class));
    }

    // ── cancel of APPROVED + scheduled_for ───────────────────────────────────

    @Test
    void cancelTransitionsApprovedScheduledQueryToCancelled() {
        var dueAt = java.time.Instant.now().plusSeconds(60);
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT, dueAt)));

        service.cancel(new CancelQueryCommand(queryId, submitterId, organizationId));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.APPROVED,
                QueryStatus.CANCELLED);
        verify(eventPublisher).publishEvent(any(QueryCancelledEvent.class));
    }

    @Test
    void cancelThrowsNotCancellableWhenApprovedButNotScheduled() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT, null)));

        assertThatThrownBy(() -> service.cancel(
                new CancelQueryCommand(queryId, submitterId, organizationId)))
                .isInstanceOf(QueryNotCancellableException.class);

        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(QueryCancelledEvent.class));
    }

    // ── executeScheduled ─────────────────────────────────────────────────────

    @Test
    void executeScheduledFiresExecutionWhenDue() {
        var dueAt = java.time.Instant.now().minusSeconds(2);
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT, dueAt)));
        when(permissionLookupService.findFor(submitterId, datasourceId))
                .thenReturn(Optional.empty());
        when(queryParser.parse(anyString(), any()))
                .thenReturn(new SqlParseResult(QueryType.SELECT, "SELECT 1"));
        when(queryExecutor.execute(any())).thenReturn(new SelectExecutionResult(
                List.of(new ResultColumn("c", 4, "int4")),
                List.of(List.of(1)),
                1L, false, Duration.ofMillis(11)));

        service.executeScheduled(queryId);

        verify(queryRequestStateService).recordExecutionOutcome(any(RecordExecutionCommand.class));
        var event = ArgumentCaptor.forClass(QueryExecutedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().finalStatus()).isEqualTo(QueryStatus.EXECUTED);

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.QUERY_EXECUTED);
        assertThat(auditCaptor.getValue().actorId()).isEqualTo(submitterId);
        assertThat(auditCaptor.getValue().metadata()).containsEntry("trigger", "scheduled");
    }

    @Test
    void executeScheduledSkipsWhenStatusIsNotApproved() {
        var dueAt = java.time.Instant.now().minusSeconds(2);
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.EXECUTED, QueryType.SELECT, dueAt)));

        service.executeScheduled(queryId);

        verify(queryExecutor, never()).execute(any());
        verify(eventPublisher, never()).publishEvent(any(QueryExecutedEvent.class));
    }

    @Test
    void executeScheduledSkipsWhenScheduleNotYetDue() {
        var future = java.time.Instant.now().plusSeconds(600);
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT, future)));

        service.executeScheduled(queryId);

        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void executeScheduledSkipsWhenScheduledForIsNull() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT, null)));

        service.executeScheduled(queryId);

        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void executeScheduledThrowsNotFoundWhenQueryGone() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.executeScheduled(queryId))
                .isInstanceOf(QueryRequestNotFoundException.class);
    }

    // ── executeBreakGlass (AF-385) ────────────────────────────────────────────

    @Test
    void executeBreakGlassRecordsBreakGlassAuditActionAndExecutes() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.APPROVED, QueryType.SELECT)));
        when(queryExecutor.execute(any())).thenReturn(new SelectExecutionResult(
                List.of(new ResultColumn("id", 4, "int4")),
                List.of(List.of(1)), 1L, false, Duration.ofMillis(10)));

        var outcome = service.executeBreakGlass(queryId, submitterId);

        assertThat(outcome.status()).isEqualTo(QueryStatus.EXECUTED);
        verify(queryRequestStateService).recordExecutionOutcome(any(RecordExecutionCommand.class));
        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action())
                .isEqualTo(AuditAction.QUERY_BREAK_GLASS_EXECUTED);
        assertThat(auditCaptor.getValue().metadata())
                .containsEntry("break_glass", true)
                .containsEntry("trigger", "break_glass");
    }

    @Test
    void executeBreakGlassThrowsWhenQueryNotApproved() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW, QueryType.SELECT)));

        assertThatThrownBy(() -> service.executeBreakGlass(queryId, submitterId))
                .isInstanceOf(QueryNotExecutableException.class);
        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void executeBreakGlassThrowsNotFoundWhenQueryGone() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.executeBreakGlass(queryId, submitterId))
                .isInstanceOf(QueryRequestNotFoundException.class);
    }

    // ── recurring series (#627) ───────────────────────────────────────────────

    private QueryRequestSnapshot recurringParent(QueryStatus status, String rule,
                                                 java.time.Instant until,
                                                 java.time.Instant nextRunAt) {
        return new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                "SELECT 1", QueryType.SELECT, false, status, null, null, null, false,
                rule, until, nextRunAt, null);
    }

    private com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor activeDescriptor() {
        return new com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor(
                datasourceId, organizationId, com.bablsoft.accessflow.core.api.DbType.POSTGRESQL,
                "h", 5432, "db", "u", "ENC", com.bablsoft.accessflow.core.api.SslMode.DISABLE,
                10, 1000, false, null, false, null, null, null, null, null, null, true);
    }

    /** Active non-admin submitter — the recurring recheck resolves role permissions per tick. */
    private void stubActiveAnalystSubmitter() {
        when(userQueryService.findById(submitterId)).thenReturn(Optional.of(
                new com.bablsoft.accessflow.core.api.UserView(submitterId, "sub@example.com",
                        "Sub", com.bablsoft.accessflow.core.api.UserRoleType.ANALYST,
                        organizationId, true,
                        com.bablsoft.accessflow.core.api.AuthProviderType.LOCAL, null, null,
                        null, false, java.time.Instant.parse("2026-01-01T00:00:00Z"))));
        when(rolePermissionResolver.resolve(any(), any())).thenReturn(java.util.Set.of());
    }

    @Test
    void cancelRecurringApprovedBySubmitterTransitionsToCancelled() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400),
                        now.plusSeconds(3600))));

        service.cancel(new CancelQueryCommand(queryId, submitterId, organizationId));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.APPROVED,
                QueryStatus.CANCELLED);
    }

    @Test
    void cancelRecurringApprovedByReviewerIsAllowed() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400),
                        now.plusSeconds(3600))));

        service.cancel(new CancelQueryCommand(queryId, otherUserId, organizationId, true));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.APPROVED,
                QueryStatus.CANCELLED);
    }

    @Test
    void cancelNonRecurringByReviewerIsDenied() {
        when(queryRequestLookupService.findById(queryId))
                .thenReturn(Optional.of(snapshot(QueryStatus.PENDING_REVIEW, QueryType.SELECT)));
        when(messageSource.getMessage(eq("error.query_not_owned_by_caller"), any(), any(Locale.class)))
                .thenReturn("not yours");

        assertThatThrownBy(() -> service.cancel(
                new CancelQueryCommand(queryId, otherUserId, organizationId, true)))
                .isInstanceOf(AccessDeniedException.class);

        verify(queryRequestStateService, never()).transitionTo(any(), any(), any());
    }

    @Test
    void executeThrowsForRecurringParentSoTheSeriesIsNeverConsumed() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400),
                        now.plusSeconds(3600))));

        assertThatThrownBy(() -> service.execute(
                new ExecuteQueryCommand(queryId, submitterId, organizationId, false)))
                .isInstanceOf(QueryNotExecutableException.class);
        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void executeRecurringOccurrenceSkipsWhenCursorMissingOrFuture() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400), null)));
        service.executeRecurringOccurrence(queryId);

        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400),
                        now.plusSeconds(600))));
        service.executeRecurringOccurrence(queryId);

        verify(queryRequestPersistenceService, never())
                .createRecurringOccurrence(any(), any(), any());
        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void executeRecurringOccurrenceCompletesSeriesWhenUntilPassed() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.minusSeconds(60),
                        now.minusSeconds(10))));

        service.executeRecurringOccurrence(queryId);

        verify(queryRequestPersistenceService).clearRecurrenceNextRun(queryId, null);
        verify(queryRequestPersistenceService, never())
                .createRecurringOccurrence(any(), any(), any());
        verify(auditLogService, never()).record(any());
    }

    @Test
    void executeRecurringOccurrenceHaltsFailClosedWhenPermissionDenied() {
        stubActiveAnalystSubmitter();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400),
                        now.minusSeconds(10))));
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(activeDescriptor()));
        org.mockito.Mockito.doThrow(new AccessDeniedException("permission revoked"))
                .when(permissionVerifier).verify(eq(submitterId), eq(datasourceId), any(), any());

        service.executeRecurringOccurrence(queryId);

        verify(queryRequestPersistenceService).clearRecurrenceNextRun(queryId, "permission revoked");
        verify(queryRequestPersistenceService, never())
                .createRecurringOccurrence(any(), any(), any());
        verify(queryExecutor, never()).execute(any());
        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.RECURRING_SERIES_HALTED);
        assertThat(auditCaptor.getValue().metadata()).containsEntry("reason", "permission revoked");
    }

    @Test
    void executeRecurringOccurrenceHaltsWhenDatasourceInactiveOrGone() {
        stubActiveAnalystSubmitter();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400),
                        now.minusSeconds(10))));
        when(datasourceLookupService.findById(datasourceId)).thenReturn(Optional.empty());

        service.executeRecurringOccurrence(queryId);

        verify(queryRequestPersistenceService)
                .clearRecurrenceNextRun(eq(queryId), anyString());
        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void executeRecurringOccurrenceHaltsWhenSqlNoLongerParses() {
        stubActiveAnalystSubmitter();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400),
                        now.minusSeconds(10))));
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(activeDescriptor()));
        when(queryParser.parse(anyString(), any()))
                .thenThrow(new com.bablsoft.accessflow.core.api.InvalidSqlException("bad sql"));

        service.executeRecurringOccurrence(queryId);

        verify(queryRequestPersistenceService).clearRecurrenceNextRun(queryId, "bad sql");
        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void executeRecurringOccurrenceCreatesChildAdvancesCursorAndExecutes() {
        stubActiveAnalystSubmitter();
        var childId = UUID.randomUUID();
        var until = now.plusSeconds(86400);
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", until, now.minusSeconds(10))));
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(activeDescriptor()));
        when(queryRequestPersistenceService.createRecurringOccurrence(eq(queryId), any(), any()))
                .thenReturn(Optional.of(childId));
        when(queryRequestLookupService.findById(childId)).thenReturn(Optional.of(
                new QueryRequestSnapshot(childId, datasourceId, organizationId, submitterId,
                        "SELECT 1", QueryType.SELECT, false, QueryStatus.APPROVED, null,
                        null, null, false, null, null, null, queryId)));
        when(queryExecutor.execute(any())).thenReturn(new SelectExecutionResult(
                List.of(new ResultColumn("id", 4, "int4")),
                List.of(List.of(1)),
                1L, false, Duration.ofMillis(9)));

        service.executeRecurringOccurrence(queryId);

        // Cursor advances by the interval from "now" — never a backfill of missed runs.
        verify(queryRequestPersistenceService)
                .createRecurringOccurrence(queryId, now.minusSeconds(10), now.plusSeconds(3600));
        var execCaptor = ArgumentCaptor.forClass(RecordExecutionCommand.class);
        verify(queryRequestStateService).recordExecutionOutcome(execCaptor.capture());
        assertThat(execCaptor.getValue().queryRequestId()).isEqualTo(childId);
        assertThat(execCaptor.getValue().outcome()).isEqualTo(QueryStatus.EXECUTED);
        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.QUERY_EXECUTED);
        assertThat(auditCaptor.getValue().metadata()).containsEntry("trigger", "recurring");
        var eventCaptor = ArgumentCaptor.forClass(QueryExecutedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().queryRequestId()).isEqualTo(childId);
        assertThat(eventCaptor.getValue().recurringParentId()).isEqualTo(queryId);
    }

    @Test
    void executeRecurringOccurrencePassesNullCursorForFinalOccurrence() {
        stubActiveAnalystSubmitter();
        // Next occurrence (now + 1h) would land beyond recurrence_until → this run is the last.
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(1800),
                        now.minusSeconds(10))));
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(activeDescriptor()));
        when(queryRequestPersistenceService.createRecurringOccurrence(queryId, now.minusSeconds(10), null))
                .thenReturn(Optional.empty());

        service.executeRecurringOccurrence(queryId);

        verify(queryRequestPersistenceService).createRecurringOccurrence(queryId, now.minusSeconds(10), null);
    }

    @Test
    void executeRecurringOccurrenceSkipsWhenRacedCancelClearedTheCursor() {
        stubActiveAnalystSubmitter();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400),
                        now.minusSeconds(10))));
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(activeDescriptor()));
        when(queryRequestPersistenceService.createRecurringOccurrence(eq(queryId), any(), any()))
                .thenReturn(Optional.empty());

        service.executeRecurringOccurrence(queryId);

        verify(queryExecutor, never()).execute(any());
        verify(queryRequestPersistenceService, never()).clearRecurrenceNextRun(any(), any());
    }

    @Test
    void executeRecurringOccurrenceSkipsPermissionGateForAdminSubmitter() {
        // Mirrors submission: an admin has no per-datasource permission row, so the recurring
        // recheck must not halt the series for lacking one.
        when(userQueryService.findById(submitterId)).thenReturn(Optional.of(
                new com.bablsoft.accessflow.core.api.UserView(submitterId, "admin@example.com",
                        "Admin", com.bablsoft.accessflow.core.api.UserRoleType.ADMIN,
                        organizationId, true,
                        com.bablsoft.accessflow.core.api.AuthProviderType.LOCAL, null, null,
                        null, false, java.time.Instant.parse("2026-01-01T00:00:00Z"))));
        when(rolePermissionResolver.resolve(any(), any()))
                .thenReturn(java.util.Set.of(
                        com.bablsoft.accessflow.core.api.Permission.QUERY_ADMIN));
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400),
                        now.minusSeconds(10))));
        when(datasourceLookupService.findById(datasourceId))
                .thenReturn(Optional.of(activeDescriptor()));
        when(queryRequestPersistenceService.createRecurringOccurrence(eq(queryId), any(), any()))
                .thenReturn(Optional.empty());

        service.executeRecurringOccurrence(queryId);

        verify(permissionVerifier, never()).verify(any(), any(), any(), any());
        verify(queryRequestPersistenceService).createRecurringOccurrence(eq(queryId), any(), any());
    }

    @Test
    void executeRecurringOccurrenceDoesNotHaltOnTransientInfrastructureError() {
        // A pool blip is not a permission loss — the exception propagates to the job's per-row
        // catch and the still-due cursor retries next tick.
        stubActiveAnalystSubmitter();
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400),
                        now.minusSeconds(10))));
        when(datasourceLookupService.findById(datasourceId))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("pool exhausted"));

        assertThatThrownBy(() -> service.executeRecurringOccurrence(queryId))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);

        verify(queryRequestPersistenceService, never()).clearRecurrenceNextRun(any(), any());
        verify(auditLogService, never()).record(any());
    }

    @Test
    void executeThrowsForOrphanedOccurrenceRow() {
        // A crash between child insert and execution leaves an APPROVED occurrence; its slot
        // was already consumed by the cursor advance, so manual execution stays blocked.
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                new QueryRequestSnapshot(queryId, datasourceId, organizationId, submitterId,
                        "SELECT 1", QueryType.SELECT, false, QueryStatus.APPROVED, null,
                        null, null, false, null, null, null, UUID.randomUUID())));

        assertThatThrownBy(() -> service.execute(
                new ExecuteQueryCommand(queryId, submitterId, organizationId, false)))
                .isInstanceOf(QueryNotExecutableException.class);
        verify(queryExecutor, never()).execute(any());
    }

    @Test
    void cancelRecurringSeriesClearsTheCursor() {
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400),
                        now.plusSeconds(3600))));

        service.cancel(new CancelQueryCommand(queryId, submitterId, organizationId));

        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.APPROVED,
                QueryStatus.CANCELLED);
        verify(queryRequestPersistenceService).clearRecurrenceNextRun(queryId, null);
    }

    @Test
    void executeRecurringOccurrenceHaltsWhenSubmitterInactive() {
        when(userQueryService.findById(submitterId)).thenReturn(Optional.empty());
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(
                recurringParent(QueryStatus.APPROVED, "PT1H", now.plusSeconds(86400),
                        now.minusSeconds(10))));

        service.executeRecurringOccurrence(queryId);

        verify(queryRequestPersistenceService).clearRecurrenceNextRun(eq(queryId), anyString());
        verify(queryExecutor, never()).execute(any());
    }
}
