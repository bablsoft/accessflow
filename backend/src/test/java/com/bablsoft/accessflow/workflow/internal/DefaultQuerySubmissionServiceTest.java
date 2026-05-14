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
import com.bablsoft.accessflow.core.api.SubmitQueryCommand;
import com.bablsoft.accessflow.core.events.QuerySubmittedEvent;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.proxy.api.InvalidSqlException;
import com.bablsoft.accessflow.proxy.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
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
import java.util.Optional;
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

    @Mock SqlParserService sqlParserService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock DatasourceUserPermissionLookupService permissionLookupService;
    @Mock QueryRequestPersistenceService queryRequestPersistenceService;
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
        stubParse("BEGIN", QueryType.OTHER);

        assertThatThrownBy(() -> service.submit(input("BEGIN", false)))
                .isInstanceOf(InvalidSqlException.class);

        verify(queryRequestPersistenceService, never()).submit(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void rejectsInactiveDatasource() {
        stubParse("SELECT 1", QueryType.SELECT);
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
        when(sqlParserService.parse("garbage"))
                .thenThrow(new InvalidSqlException("cannot parse"));

        assertThatThrownBy(() -> service.submit(input("garbage", false)))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("cannot parse");

        verify(queryRequestPersistenceService, never()).submit(any());
    }

    @Test
    void persistsCommandWithParsedTypeAndJustification() {
        stubParse("SELECT 1", QueryType.SELECT);
        stubActiveDatasourceForUser();
        stubPermission(true, false, false, null);
        stubPersist();

        service.submit(new SubmissionInput(datasourceId, "SELECT 1", "ticket-42",
                userId, organizationId, false));

        ArgumentCaptor<SubmitQueryCommand> captor = ArgumentCaptor.forClass(SubmitQueryCommand.class);
        verify(queryRequestPersistenceService).submit(captor.capture());
        var cmd = captor.getValue();
        assertThat(cmd.datasourceId()).isEqualTo(datasourceId);
        assertThat(cmd.submittedByUserId()).isEqualTo(userId);
        assertThat(cmd.sqlText()).isEqualTo("SELECT 1");
        assertThat(cmd.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(cmd.justification()).isEqualTo("ticket-42");
    }

    private SubmissionInput input(String sql, boolean isAdmin) {
        return new SubmissionInput(datasourceId, sql, null, userId, organizationId, isAdmin);
    }

    private void stubParse(String sql, QueryType type) {
        when(sqlParserService.parse(sql)).thenReturn(new SqlParseResult(type, sql));
    }

    private void stubActiveDatasourceForUser() {
        when(datasourceAdminService.getForUser(datasourceId, organizationId, userId))
                .thenReturn(datasourceView(true));
    }

    private void stubPermission(boolean canRead, boolean canWrite, boolean canDdl,
                                Instant expiresAt) {
        when(permissionLookupService.findFor(eq(userId), eq(datasourceId)))
                .thenReturn(Optional.of(new DatasourceUserPermissionView(
                        UUID.randomUUID(), userId, datasourceId,
                        canRead, canWrite, canDdl,
                        List.of(), List.of(), List.of(), expiresAt)));
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
                false, false, null, true, null, null, null, active, Instant.now());
    }
}
