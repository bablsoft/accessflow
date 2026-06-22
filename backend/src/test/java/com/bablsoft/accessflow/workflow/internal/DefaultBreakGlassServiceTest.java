package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryRequestPersistenceService;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.QuotaService;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.SubmitQueryCommand;
import com.bablsoft.accessflow.core.events.QuerySubmittedEvent;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.workflow.api.BreakGlassNotPermittedException;
import com.bablsoft.accessflow.workflow.api.BreakGlassService.BreakGlassInput;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService.ExecutionOutcome;
import com.bablsoft.accessflow.workflow.events.BreakGlassExecutedEvent;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.BreakGlassEventEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.BreakGlassEventRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
class DefaultBreakGlassServiceTest {

    @Mock QueryParser queryParser;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock QuotaService quotaService;
    @Mock QueryRequestPersistenceService queryRequestPersistenceService;
    @Mock QueryRequestStateService queryRequestStateService;
    @Mock QueryLifecycleService queryLifecycleService;
    @Mock BreakGlassEventRepository breakGlassEventRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MessageSource messageSource;

    DefaultBreakGlassService service;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID queryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultBreakGlassService(queryParser, datasourceAdminService,
                permissionLookupService, quotaService, queryRequestPersistenceService,
                queryRequestStateService, queryLifecycleService, breakGlassEventRepository,
                eventPublisher, messageSource);
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("msg");
        when(breakGlassEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void breakGlassExecutesImmediatelyAndOpensRetroReview() {
        stubDatasourceForUser(true);
        stubParse("SELECT 1", QueryType.SELECT, Set.of());
        stubPermission(true, false, false, true, null, List.of(), List.of());
        when(queryRequestPersistenceService.submit(any())).thenReturn(queryId);
        when(queryLifecycleService.executeBreakGlass(queryId, userId))
                .thenReturn(new ExecutionOutcome(queryId, QueryStatus.EXECUTED, 3L, 12));

        var result = service.breakGlassExecute(input("SELECT 1", false));

        assertThat(result.queryRequestId()).isEqualTo(queryId);
        assertThat(result.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(result.rowsAffected()).isEqualTo(3L);

        // EMERGENCY_ACCESS reason, no QuerySubmittedEvent (AI/review bypassed).
        var cmd = ArgumentCaptor.forClass(SubmitQueryCommand.class);
        verify(queryRequestPersistenceService).submit(cmd.capture());
        assertThat(cmd.getValue().submissionReason()).isEqualTo(SubmissionReason.EMERGENCY_ACCESS);
        verify(eventPublisher, never()).publishEvent(any(QuerySubmittedEvent.class));

        // Force-approve then execute.
        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.APPROVED);
        verify(queryLifecycleService).executeBreakGlass(queryId, userId);

        // A PENDING_REVIEW retro-review row is created and the fanout event published.
        var entity = ArgumentCaptor.forClass(BreakGlassEventEntity.class);
        verify(breakGlassEventRepository).save(entity.capture());
        assertThat(entity.getValue().getQueryRequestId()).isEqualTo(queryId);
        assertThat(entity.getValue().getJustification()).isEqualTo("prod is down");
        verify(eventPublisher).publishEvent(any(BreakGlassExecutedEvent.class));
    }

    @Test
    void adminAlsoRequiresExplicitBreakGlassGrant() {
        stubDatasourceForAdmin(true);
        stubParse("SELECT 1", QueryType.SELECT, Set.of());
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.breakGlassExecute(input("SELECT 1", true)))
                .isInstanceOf(BreakGlassNotPermittedException.class);

        verify(queryRequestPersistenceService, never()).submit(any());
        verify(queryLifecycleService, never()).executeBreakGlass(any(), any());
    }

    @Test
    void deniesWhenNoPermissionRow() {
        stubDatasourceForUser(true);
        stubParse("SELECT 1", QueryType.SELECT, Set.of());
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.breakGlassExecute(input("SELECT 1", false)))
                .isInstanceOf(BreakGlassNotPermittedException.class);
    }

    @Test
    void deniesWhenFlagNotGranted() {
        stubDatasourceForUser(true);
        stubParse("SELECT 1", QueryType.SELECT, Set.of());
        stubPermission(true, false, false, false, null, List.of(), List.of());

        assertThatThrownBy(() -> service.breakGlassExecute(input("SELECT 1", false)))
                .isInstanceOf(BreakGlassNotPermittedException.class);
    }

    @Test
    void deniesWhenGrantExpired() {
        stubDatasourceForUser(true);
        stubParse("SELECT 1", QueryType.SELECT, Set.of());
        stubPermission(true, false, false, true, Instant.now().minusSeconds(60), List.of(), List.of());

        assertThatThrownBy(() -> service.breakGlassExecute(input("SELECT 1", false)))
                .isInstanceOf(BreakGlassNotPermittedException.class);
    }

    @Test
    void deniesWhenMissingCapabilityForQueryType() {
        stubDatasourceForUser(true);
        stubParse("DELETE FROM t", QueryType.DELETE, Set.of("t"));
        // break-glass granted + read only, but query is a DELETE (needs canWrite)
        stubPermission(true, false, false, true, null, List.of(), List.of());

        assertThatThrownBy(() -> service.breakGlassExecute(input("DELETE FROM t", false)))
                .isInstanceOf(BreakGlassNotPermittedException.class);
    }

    @Test
    void deniesWhenTableOutsideAllowList() {
        stubDatasourceForUser(true);
        stubParse("SELECT * FROM secrets", QueryType.SELECT, Set.of("secrets"));
        stubPermission(true, false, false, true, null, List.of(), List.of("orders"));

        assertThatThrownBy(() -> service.breakGlassExecute(input("SELECT * FROM secrets", false)))
                .isInstanceOf(BreakGlassNotPermittedException.class);
    }

    @Test
    void rejectsQueryTypeOther() {
        stubDatasourceForUser(true);
        stubParse("BEGIN", QueryType.OTHER, Set.of());

        assertThatThrownBy(() -> service.breakGlassExecute(input("BEGIN", false)))
                .isInstanceOf(InvalidSqlException.class);

        verify(queryRequestPersistenceService, never()).submit(any());
    }

    @Test
    void rejectsInactiveDatasource() {
        stubDatasourceForUser(false);

        assertThatThrownBy(() -> service.breakGlassExecute(input("SELECT 1", false)))
                .isInstanceOf(DatasourceUnavailableException.class);
    }

    @Test
    void rejectsWhenQuotaExceeded() {
        stubDatasourceForUser(true);
        org.mockito.Mockito.doThrow(new com.bablsoft.accessflow.core.api.QuotaExceededException(
                        com.bablsoft.accessflow.core.api.QuotaType.QUERIES_PER_DAY,
                        organizationId, 100, 100))
                .when(quotaService).checkQueryQuota(organizationId);

        assertThatThrownBy(() -> service.breakGlassExecute(input("SELECT 1", false)))
                .isInstanceOf(com.bablsoft.accessflow.core.api.QuotaExceededException.class);

        verify(queryRequestPersistenceService, never()).submit(any());
    }

    private BreakGlassInput input(String sql, boolean isAdmin) {
        return new BreakGlassInput(datasourceId, sql, "prod is down", userId, organizationId,
                isAdmin, "10.0.0.1", "agent");
    }

    private void stubParse(String sql, QueryType type, Set<String> tables) {
        when(queryParser.parse(eq(sql), any()))
                .thenReturn(new SqlParseResult(type, false, List.of(sql), tables));
    }

    private void stubDatasourceForUser(boolean active) {
        when(datasourceAdminService.getForUser(datasourceId, organizationId, userId))
                .thenReturn(datasourceView(active));
    }

    private void stubDatasourceForAdmin(boolean active) {
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenReturn(datasourceView(active));
    }

    private void stubPermission(boolean canRead, boolean canWrite, boolean canDdl,
                                boolean canBreakGlass, Instant expiresAt,
                                List<String> allowedSchemas, List<String> allowedTables) {
        when(permissionLookupService.findFor(userId, datasourceId))
                .thenReturn(Optional.of(new DatasourceUserPermissionView(
                        UUID.randomUUID(), userId, datasourceId,
                        canRead, canWrite, canDdl, canBreakGlass,
                        allowedSchemas, allowedTables, List.of(), expiresAt)));
    }

    private DatasourceView datasourceView(boolean active) {
        return new DatasourceView(
                datasourceId, organizationId, "test", DbType.POSTGRESQL,
                "localhost", 5432, "appdb", "svc", SslMode.DISABLE, 5, 1000,
                false, false, null, true, null, false, null, null, null,
                null, null, active, Instant.now());
    }
}
