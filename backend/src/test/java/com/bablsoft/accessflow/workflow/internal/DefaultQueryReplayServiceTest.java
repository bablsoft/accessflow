package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Column;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Schema;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Table;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.workflow.api.QueryReplayService.ReplayCommand;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotNotFoundException;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService.QuerySubmissionResult;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService.SubmissionInput;
import com.bablsoft.accessflow.workflow.api.ReplaySchemaIncompatibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryReplayServiceTest {

    @Mock QuerySnapshotService querySnapshotService;
    @Mock QuerySubmissionService querySubmissionService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock MessageSource messageSource;

    private DefaultQueryReplayService service;

    private final UUID originalQueryId = UUID.randomUUID();
    private final UUID sourceDsId = UUID.randomUUID();
    private final UUID targetDsId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultQueryReplayService(querySnapshotService, querySubmissionService,
                datasourceAdminService, new SchemaHasher(), messageSource);
        lenient().when(messageSource.getMessage(any(), any(), any())).thenReturn("message");
    }

    private ReplayCommand command(boolean isAdmin) {
        return new ReplayCommand(originalQueryId, targetDsId, userId, orgId, isAdmin,
                "1.2.3.4", "curl");
    }

    private QuerySnapshotView snapshot(DbType dbType, List<String> referenced) {
        return new QuerySnapshotView(UUID.randomUUID(), originalQueryId, orgId, sourceDsId, userId,
                "SELECT * FROM users", QueryType.SELECT, false, dbType, referenced, "src-hash",
                "{}", "[]", 3L, 9, Instant.now(), Instant.now());
    }

    private DatasourceView target(DbType dbType, boolean active) {
        return new DatasourceView(targetDsId, orgId, "test-db", dbType, "h", 5432, "db", "u",
                SslMode.DISABLE, 5, 1000, false, true, null, false, null, false, null, null, null,
                null, null, active, Instant.now());
    }

    private DatabaseSchemaView schemaWith(String... tables) {
        var tableList = java.util.Arrays.stream(tables)
                .map(t -> new Table(t, List.of(new Column("id", "int4", false, true)), List.of()))
                .toList();
        return new DatabaseSchemaView(List.of(new Schema("public", tableList)));
    }

    @Test
    void replaysThroughWorkflowWithCallerAsSubmitter() {
        var newId = UUID.randomUUID();
        when(querySnapshotService.find(originalQueryId, orgId))
                .thenReturn(Optional.of(snapshot(DbType.POSTGRESQL, List.of("public.users"))));
        when(datasourceAdminService.getForUser(targetDsId, orgId, userId))
                .thenReturn(target(DbType.POSTGRESQL, true));
        when(datasourceAdminService.introspectSchemaForSystem(targetDsId, orgId))
                .thenReturn(schemaWith("users", "orders"));
        when(querySubmissionService.submit(any()))
                .thenReturn(new QuerySubmissionResult(newId, QueryStatus.PENDING_AI));

        var result = service.replay(command(false));

        assertThat(result.newQueryId()).isEqualTo(newId);
        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_AI);
        assertThat(result.sourceSchemaHash()).isEqualTo("src-hash");
        assertThat(result.targetSchemaHash()).hasSize(64);
        assertThat(result.sourceDatasourceId()).isEqualTo(sourceDsId);
        assertThat(result.targetDatasourceId()).isEqualTo(targetDsId);

        var captor = ArgumentCaptor.forClass(SubmissionInput.class);
        verify(querySubmissionService).submit(captor.capture());
        var input = captor.getValue();
        assertThat(input.datasourceId()).isEqualTo(targetDsId);
        assertThat(input.sql()).isEqualTo("SELECT * FROM users");
        assertThat(input.submitterUserId()).isEqualTo(userId);
        assertThat(input.organizationId()).isEqualTo(orgId);
        assertThat(input.scheduledFor()).isNull();
        assertThat(input.submissionReason()).isEqualTo(SubmissionReason.USER_SUBMITTED);
        assertThat(input.ciCdOrigin()).isFalse();
        assertThat(input.submittedIp()).isEqualTo("1.2.3.4");
        assertThat(input.submittedUserAgent()).isEqualTo("curl");
    }

    @Test
    void adminResolvesTargetViaAdminLookup() {
        var newId = UUID.randomUUID();
        when(querySnapshotService.find(originalQueryId, orgId))
                .thenReturn(Optional.of(snapshot(DbType.POSTGRESQL, List.of())));
        when(datasourceAdminService.getForAdmin(targetDsId, orgId))
                .thenReturn(target(DbType.POSTGRESQL, true));
        when(datasourceAdminService.introspectSchemaForSystem(targetDsId, orgId))
                .thenReturn(schemaWith("users"));
        when(querySubmissionService.submit(any()))
                .thenReturn(new QuerySubmissionResult(newId, QueryStatus.PENDING_AI));

        service.replay(command(true));

        verify(datasourceAdminService).getForAdmin(targetDsId, orgId);
        verify(datasourceAdminService, org.mockito.Mockito.never()).getForUser(any(), any(), any());
    }

    @Test
    void throwsWhenSnapshotMissing() {
        when(querySnapshotService.find(originalQueryId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replay(command(false)))
                .isInstanceOf(QuerySnapshotNotFoundException.class);
    }

    @Test
    void rejectsInactiveTarget() {
        when(querySnapshotService.find(originalQueryId, orgId))
                .thenReturn(Optional.of(snapshot(DbType.POSTGRESQL, List.of("public.users"))));
        when(datasourceAdminService.getForUser(targetDsId, orgId, userId))
                .thenReturn(target(DbType.POSTGRESQL, false));

        assertThatThrownBy(() -> service.replay(command(false)))
                .isInstanceOf(DatasourceUnavailableException.class);
    }

    @Test
    void rejectsDbTypeMismatch() {
        when(querySnapshotService.find(originalQueryId, orgId))
                .thenReturn(Optional.of(snapshot(DbType.POSTGRESQL, List.of("public.users"))));
        when(datasourceAdminService.getForUser(targetDsId, orgId, userId))
                .thenReturn(target(DbType.MYSQL, true));

        assertThatThrownBy(() -> service.replay(command(false)))
                .isInstanceOf(ReplaySchemaIncompatibleException.class)
                .satisfies(ex -> assertThat(((ReplaySchemaIncompatibleException) ex).reason())
                        .isEqualTo(ReplaySchemaIncompatibleException.Reason.DB_TYPE_MISMATCH));
    }

    @Test
    void rejectsMissingReferencedTables() {
        when(querySnapshotService.find(originalQueryId, orgId))
                .thenReturn(Optional.of(snapshot(DbType.POSTGRESQL, List.of("public.payments"))));
        when(datasourceAdminService.getForUser(targetDsId, orgId, userId))
                .thenReturn(target(DbType.POSTGRESQL, true));
        when(datasourceAdminService.introspectSchemaForSystem(targetDsId, orgId))
                .thenReturn(schemaWith("users"));

        assertThatThrownBy(() -> service.replay(command(false)))
                .isInstanceOf(ReplaySchemaIncompatibleException.class)
                .satisfies(ex -> {
                    var sie = (ReplaySchemaIncompatibleException) ex;
                    assertThat(sie.reason())
                            .isEqualTo(ReplaySchemaIncompatibleException.Reason.MISSING_TABLES);
                    assertThat(sie.missingTables()).containsExactly("public.payments");
                });
    }

    @Test
    void failsClosedWhenTargetIntrospectionThrows() {
        when(querySnapshotService.find(originalQueryId, orgId))
                .thenReturn(Optional.of(snapshot(DbType.POSTGRESQL, List.of("public.users"))));
        when(datasourceAdminService.getForUser(targetDsId, orgId, userId))
                .thenReturn(target(DbType.POSTGRESQL, true));
        when(datasourceAdminService.introspectSchemaForSystem(targetDsId, orgId))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.replay(command(false)))
                .isInstanceOf(ReplaySchemaIncompatibleException.class)
                .satisfies(ex -> assertThat(((ReplaySchemaIncompatibleException) ex).reason())
                        .isEqualTo(ReplaySchemaIncompatibleException.Reason.TARGET_SCHEMA_UNAVAILABLE));
    }
}
