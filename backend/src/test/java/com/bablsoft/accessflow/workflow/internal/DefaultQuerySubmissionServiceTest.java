package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionView;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryRequestPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.SubmitQueryCommand;
import com.bablsoft.accessflow.core.events.QuerySubmittedEvent;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService.SubmissionInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQuerySubmissionServiceTest {

    @Mock QueryParser queryParser;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock QueryRequestPersistenceService queryRequestPersistenceService;
    @Mock com.bablsoft.accessflow.core.api.QuotaService quotaService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock MessageSource messageSource;
    @InjectMocks DefaultQuerySubmissionService service;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();

    @Test
    void submitsSelectAsAnalystWithReadPermission() {
        stubParse("SELECT 1", QueryType.SELECT);
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null);
        var queryId = stubPersist();

        var result = service.submit(input("SELECT 1", false));

        assertThat(result.id()).isEqualTo(queryId);
        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_AI);
        verify(eventPublisher).publishEvent(new QuerySubmittedEvent(queryId));
    }

    @Test
    void submitsUpdateAsAnalystWithWritePermission() {
        stubParse("UPDATE t SET x=1", QueryType.UPDATE);
        stubActiveDatasourceForUser();
        stubPermission(false, true, false, null);
        var queryId = stubPersist();

        var result = service.submit(input("UPDATE t SET x=1", false));

        assertThat(result.id()).isEqualTo(queryId);
        verify(eventPublisher).publishEvent(any(QuerySubmittedEvent.class));
    }

    @Test
    void submitsDdlAsAnalystWithDdlPermission() {
        stubParse("CREATE TABLE t (id int)", QueryType.DDL);
        stubActiveDatasourceForUser();
        stubPermission(false, false, true, null);
        stubPersist();

        var result = service.submit(input("CREATE TABLE t (id int)", false));

        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_AI);
    }

    @Test
    void rejectsQueryTypeOther() {
        stubActiveDatasourceForUser();
        stubParse("BEGIN", QueryType.OTHER);

        assertThatThrownBy(() -> service.submit(input("BEGIN", false)))
                .isInstanceOf(InvalidSqlException.class);

        verify(queryRequestPersistenceService, never()).submit(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rejectsWhenQueryQuotaExceeded() {
        stubActiveDatasourceForUser();
        org.mockito.Mockito.doThrow(new com.bablsoft.accessflow.core.api.QuotaExceededException(
                        com.bablsoft.accessflow.core.api.QuotaType.QUERIES_PER_DAY,
                        organizationId, 100, 100))
                .when(quotaService).checkQueryQuota(organizationId);

        assertThatThrownBy(() -> service.submit(input("SELECT 1", false)))
                .isInstanceOf(com.bablsoft.accessflow.core.api.QuotaExceededException.class);

        verify(queryRequestPersistenceService, never()).submit(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rejectsInactiveDatasource() {
        when(datasourceAdminService.getForUser(datasourceId, organizationId, userId))
                .thenReturn(datasourceView(false));

        assertThatThrownBy(() -> service.submit(input("SELECT 1", false)))
                .isInstanceOf(DatasourceUnavailableException.class);

        verify(queryRequestPersistenceService, never()).submit(any());
    }

    @Test
    void rejectsSubmitterWithoutPermissionRow() {
        stubParse("SELECT 1", QueryType.SELECT);
        stubActiveDatasourceForUser();
        when(permissionLookupService.findFor(userId, datasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(input("SELECT 1", false)))
                .isInstanceOf(AccessDeniedException.class);

        verify(queryRequestPersistenceService, never()).submit(any());
    }

    @Test
    void rejectsSubmitterWithExpiredPermission() {
        stubParse("SELECT 1", QueryType.SELECT);
        stubActiveDatasourceForUser();
        stubPermission(true, true, true, Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> service.submit(input("SELECT 1", false)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsCapMismatchReadOnlyAttemptsUpdate() {
        stubParse("UPDATE t SET x=1", QueryType.UPDATE);
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null);

        assertThatThrownBy(() -> service.submit(input("UPDATE t SET x=1", false)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminBypassesPermissionLookup() {
        stubParse("SELECT 1", QueryType.SELECT);
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenReturn(datasourceView(true));
        var queryId = stubPersist();

        var result = service.submit(input("SELECT 1", true));

        assertThat(result.id()).isEqualTo(queryId);
        verify(permissionLookupService, never()).findFor(any(), any());
        verify(eventPublisher).publishEvent(new QuerySubmittedEvent(queryId));
    }

    @Test
    void parserFailurePropagates() {
        stubActiveDatasourceForUser();
        when(queryParser.parse(eq("garbage"), any()))
                .thenThrow(new InvalidSqlException("cannot parse"));

        assertThatThrownBy(() -> service.submit(input("garbage", false)))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("cannot parse");

        verify(queryRequestPersistenceService, never()).submit(any());
    }

    @Test
    void allowListEmptyAcceptsAnyTable() {
        stubParse("SELECT * FROM public.orders", QueryType.SELECT, Set.of("public.orders"));
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null, List.of(), List.of());
        stubPersist();

        var result = service.submit(input("SELECT * FROM public.orders", false));

        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_AI);
    }

    @Test
    void allowListNullAcceptsAnyTable() {
        stubParse("SELECT * FROM public.orders", QueryType.SELECT, Set.of("public.orders"));
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null, null, null);
        stubPersist();

        var result = service.submit(input("SELECT * FROM public.orders", false));

        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_AI);
    }

    @Test
    void schemaOnlyAllowListAcceptsTableInSchema() {
        stubParse("SELECT * FROM public.orders", QueryType.SELECT, Set.of("public.orders"));
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null, List.of("public"), List.of());
        stubPersist();

        var result = service.submit(input("SELECT * FROM public.orders", false));

        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_AI);
    }

    @Test
    void schemaOnlyAllowListRejectsTableInOtherSchema() {
        stubParse("SELECT * FROM secrets.api_tokens", QueryType.SELECT,
                Set.of("secrets.api_tokens"));
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null, List.of("public"), List.of());
        stubAllowListMessageSource();

        assertThatThrownBy(() -> service.submit(input("SELECT * FROM secrets.api_tokens", false)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("secrets.api_tokens");

        verify(queryRequestPersistenceService, never()).submit(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void schemaOnlyAllowListRejectsUnqualifiedReference() {
        stubParse("SELECT * FROM users", QueryType.SELECT, Set.of("users"));
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null, List.of("public"), List.of());
        stubAllowListMessageSource();

        assertThatThrownBy(() -> service.submit(input("SELECT * FROM users", false)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("users");

        verify(queryRequestPersistenceService, never()).submit(any());
    }

    @Test
    void tableOnlyAllowListAcceptsExactMatch() {
        stubParse("SELECT * FROM public.users", QueryType.SELECT, Set.of("public.users"));
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null, List.of(), List.of("public.users"));
        stubPersist();

        var result = service.submit(input("SELECT * FROM public.users", false));

        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_AI);
    }

    @Test
    void tableOnlyAllowListRejectsOtherTable() {
        stubParse("SELECT * FROM public.orders", QueryType.SELECT, Set.of("public.orders"));
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null, List.of(), List.of("public.users"));
        stubAllowListMessageSource();

        assertThatThrownBy(() -> service.submit(input("SELECT * FROM public.orders", false)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("public.orders");

        verify(queryRequestPersistenceService, never()).submit(any());
    }

    @Test
    void mixedAllowListAcceptsEitherDimension() {
        stubParse("SELECT a.id, b.id FROM public.a a JOIN secrets.audit b ON a.id=b.id",
                QueryType.SELECT, Set.of("public.a", "secrets.audit"));
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null, List.of("public"), List.of("secrets.audit"));
        stubPersist();

        var result = service.submit(input(
                "SELECT a.id, b.id FROM public.a a JOIN secrets.audit b ON a.id=b.id", false));

        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_AI);
    }

    @Test
    void mixedAllowListRejectsTableOutsideBothDimensions() {
        stubParse("SELECT * FROM secrets.api_tokens", QueryType.SELECT,
                Set.of("secrets.api_tokens"));
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null, List.of("public"), List.of("secrets.audit"));
        stubAllowListMessageSource();

        assertThatThrownBy(() -> service.submit(input("SELECT * FROM secrets.api_tokens", false)))
                .isInstanceOf(AccessDeniedException.class);

        verify(queryRequestPersistenceService, never()).submit(any());
    }

    @Test
    void allowListMatchIsCaseInsensitive() {
        // Parser normalizes to lowercase, but admin may have entered mixed-case allow-list values.
        stubParse("SELECT * FROM Public.Users", QueryType.SELECT, Set.of("public.users"));
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null, List.of(), List.of("Public.Users"));
        stubPersist();

        var result = service.submit(input("SELECT * FROM Public.Users", false));

        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_AI);
    }

    @Test
    void transactionalBatchUnionOfTablesIsChecked() {
        String sql = "BEGIN; INSERT INTO public.a (id) VALUES (1); "
                + "UPDATE secrets.b SET x=1 WHERE y=2; COMMIT;";
        when(queryParser.parse(eq(sql), any())).thenReturn(new SqlParseResult(
                QueryType.INSERT, true,
                List.of("INSERT INTO public.a (id) VALUES (1)",
                        "UPDATE secrets.b SET x = 1 WHERE y = 2"),
                Set.of("public.a", "secrets.b")));
        stubActiveDatasourceForUser();
        stubPermission(true, true, false, null, List.of("public"), List.of());
        stubAllowListMessageSource();

        assertThatThrownBy(() -> service.submit(input(sql, false)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("secrets.b");

        verify(queryRequestPersistenceService, never()).submit(any());
    }

    @Test
    void adminBypassesAllowList() {
        stubParse("SELECT * FROM secrets.api_tokens", QueryType.SELECT,
                Set.of("secrets.api_tokens"));
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenReturn(datasourceView(true));
        stubPersist();

        var result = service.submit(input("SELECT * FROM secrets.api_tokens", true));

        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_AI);
        verify(permissionLookupService, never()).findFor(any(), any());
    }

    @Test
    void persistsCommandWithParsedTypeAndJustification() {
        stubParse("SELECT 1", QueryType.SELECT);
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null);
        stubPersist();

        service.submit(new SubmissionInput(datasourceId, "SELECT 1", "ticket-42",
                userId, organizationId, false, null, null, null, null, false));

        ArgumentCaptor<SubmitQueryCommand> captor = ArgumentCaptor.forClass(SubmitQueryCommand.class);
        verify(queryRequestPersistenceService).submit(captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.datasourceId()).isEqualTo(datasourceId);
        assertThat(cmd.submittedByUserId()).isEqualTo(userId);
        assertThat(cmd.sqlText()).isEqualTo("SELECT 1");
        assertThat(cmd.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(cmd.justification()).isEqualTo("ticket-42");
        assertThat(cmd.scheduledFor()).isNull();
        assertThat(cmd.submissionReason()).isEqualTo(SubmissionReason.USER_SUBMITTED);
    }

    @Test
    void persistsClientContextOntoCommand() {
        stubParse("SELECT 1", QueryType.SELECT);
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null);
        stubPersist();

        service.submit(new SubmissionInput(datasourceId, "SELECT 1", "ticket-42",
                userId, organizationId, false, null, null, "203.0.113.7", "curl/8.4.0", true));

        ArgumentCaptor<SubmitQueryCommand> captor = ArgumentCaptor.forClass(SubmitQueryCommand.class);
        verify(queryRequestPersistenceService).submit(captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.submittedIp()).isEqualTo("203.0.113.7");
        assertThat(cmd.submittedUserAgent()).isEqualTo("curl/8.4.0");
        assertThat(cmd.ciCdOrigin()).isTrue();
    }

    @Test
    void propagatesAiSuggestionSubmissionReason() {
        stubParse("SELECT 1", QueryType.SELECT);
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null);
        stubPersist();

        service.submit(new SubmissionInput(datasourceId, "SELECT 1", "ticket-42",
                userId, organizationId, false, null, SubmissionReason.AI_SUGGESTION,
                null, null, false));

        ArgumentCaptor<SubmitQueryCommand> captor = ArgumentCaptor.forClass(SubmitQueryCommand.class);
        verify(queryRequestPersistenceService).submit(captor.capture());
        assertThat(captor.getValue().submissionReason()).isEqualTo(SubmissionReason.AI_SUGGESTION);
    }

    @Test
    void persistsScheduledForWhenSubmissionInputProvidesIt() {
        stubParse("SELECT 1", QueryType.SELECT);
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null);
        stubPersist();

        var futureInstant = java.time.Instant.now().plusSeconds(600);
        service.submit(new SubmissionInput(datasourceId, "SELECT 1", "ticket-42",
                userId, organizationId, false, futureInstant, null, null, null, false));

        ArgumentCaptor<SubmitQueryCommand> captor = ArgumentCaptor.forClass(SubmitQueryCommand.class);
        verify(queryRequestPersistenceService).submit(captor.capture());
        assertThat(captor.getValue().scheduledFor()).isEqualTo(futureInstant);
    }

    private SubmissionInput input(String sql, boolean isAdmin) {
        return new SubmissionInput(datasourceId, sql, null, userId, organizationId, isAdmin, null,
                null, null, null, false);
    }

    private void stubParse(String sql, QueryType type) {
        stubParse(sql, type, Set.of());
    }

    private void stubParse(String sql, QueryType type, Set<String> referencedTables) {
        when(queryParser.parse(eq(sql), any())).thenReturn(
                new SqlParseResult(type, false, List.of(sql), referencedTables));
    }

    private void stubActiveDatasourceForUser() {
        when(datasourceAdminService.getForUser(datasourceId, organizationId, userId))
                .thenReturn(datasourceView(true));
    }

    private void stubPermission(boolean canRead, boolean canWrite, boolean canDdl,
                                Instant expiresAt) {
        stubPermission(canRead, canWrite, canDdl, expiresAt, List.of(), List.of());
    }

    private void stubPermission(boolean canRead, boolean canWrite, boolean canDdl,
                                Instant expiresAt,
                                List<String> allowedSchemas, List<String> allowedTables) {
        when(permissionLookupService.findFor(eq(userId), eq(datasourceId)))
                .thenReturn(Optional.of(new DatasourceUserPermissionView(
                        UUID.randomUUID(), userId, datasourceId,
                        canRead, canWrite, canDdl,
                        allowedSchemas, allowedTables, List.of(), expiresAt)));
    }

    private void stubAllowListMessageSource() {
        when(messageSource.getMessage(eq("error.permission.table_not_allowed"),
                any(Object[].class), any(Locale.class)))
                .thenAnswer(inv -> {
                    Object[] args = inv.getArgument(1);
                    return "Disallowed tables: " + args[0];
                });
    }

    private UUID stubPersist() {
        var id = UUID.randomUUID();
        when(queryRequestPersistenceService.submit(any())).thenReturn(id);
        return id;
    }

    private DatasourceView datasourceView(boolean active) {
        return new DatasourceView(
                datasourceId, organizationId, "test", DbType.POSTGRESQL,
                "localhost", 5432, "appdb", "svc", SslMode.DISABLE, 5, 1000,
                false, false, null, true, null, false, null, null, null,
                null, null, active, Instant.now());
    }
}
