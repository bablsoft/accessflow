package com.bablsoft.accessflow.mcp.internal.tools;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpToolServiceTest {

    @Mock DatasourceAdminService datasourceAdminService;
    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock QueryResultPersistenceService queryResultPersistenceService;
    @Mock QuerySubmissionService querySubmissionService;
    @Mock QueryLifecycleService queryLifecycleService;

    McpToolService tools;
    UUID userId;
    UUID orgId;

    @BeforeEach
    void setUp() {
        var currentUser = new McpCurrentUser();
        tools = new McpToolService(currentUser, datasourceAdminService, queryRequestLookupService,
                queryResultPersistenceService, querySubmissionService, queryLifecycleService);
        userId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        authenticateAs(UserRoleType.ANALYST);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void list_datasources_uses_user_scoped_lookup_for_non_admin() {
        var view = newDatasourceView();
        when(datasourceAdminService.listForUser(any(), any(), any())).thenReturn(
                new PageResponse<>(List.of(view), 0, 20, 1, 1));

        var page = tools.listDatasources(0, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).id()).isEqualTo(view.id());
        verify(datasourceAdminService).listForUser(orgId, userId,
                com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));
    }

    @Test
    void list_datasources_uses_admin_lookup_for_admin() {
        authenticateAs(UserRoleType.ADMIN);
        when(datasourceAdminService.listForAdmin(any(), any())).thenReturn(
                new PageResponse<>(List.of(), 0, 20, 0, 0));
        tools.listDatasources(null, null);
        verify(datasourceAdminService).listForAdmin(orgId,
                com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));
    }

    @Test
    void list_my_queries_scopes_filter_to_caller() {
        when(queryRequestLookupService.findForOrganization(any(QueryListFilter.class), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        tools.listMyQueries("pending_review", null, null, 0, 20);

        var captor = ArgumentCaptor.forClass(QueryListFilter.class);
        verify(queryRequestLookupService).findForOrganization(captor.capture(), any());
        var filter = captor.getValue();
        assertThat(filter.organizationId()).isEqualTo(orgId);
        assertThat(filter.submittedByUserId()).isEqualTo(userId);
        assertThat(filter.status()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }

    @Test
    void get_query_status_returns_detail_for_owner() {
        var detail = newQueryDetail(userId, QueryStatus.PENDING_REVIEW, QueryType.SELECT);
        when(queryRequestLookupService.findDetailById(detail.id(), orgId))
                .thenReturn(Optional.of(detail));
        var result = tools.getQueryStatus(detail.id());
        assertThat(result.id()).isEqualTo(detail.id());
        assertThat(result.status()).isEqualTo(QueryStatus.PENDING_REVIEW.name());
    }

    @Test
    void get_query_status_throws_access_denied_for_other_user() {
        var detail = newQueryDetail(UUID.randomUUID(), QueryStatus.PENDING_REVIEW, QueryType.SELECT);
        when(queryRequestLookupService.findDetailById(detail.id(), orgId))
                .thenReturn(Optional.of(detail));
        assertThatThrownBy(() -> tools.getQueryStatus(detail.id()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void get_query_status_throws_not_found_when_missing() {
        var missing = UUID.randomUUID();
        when(queryRequestLookupService.findDetailById(missing, orgId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tools.getQueryStatus(missing))
                .isInstanceOf(QueryRequestNotFoundException.class);
    }

    @Test
    void get_query_result_requires_executed_select() {
        var detail = newQueryDetail(userId, QueryStatus.EXECUTED, QueryType.SELECT);
        when(queryRequestLookupService.findDetailById(detail.id(), orgId))
                .thenReturn(Optional.of(detail));
        when(queryResultPersistenceService.find(detail.id())).thenReturn(Optional.of(
                new QueryResultPersistenceService.QueryResultSnapshot(
                        detail.id(), "[]", "[]", 0L, false, 12)));
        var result = tools.getQueryResult(detail.id());
        assertThat(result.rowCount()).isZero();
        assertThat(result.durationMs()).isEqualTo(12);
    }

    @Test
    void get_query_result_rejects_non_select() {
        var detail = newQueryDetail(userId, QueryStatus.EXECUTED, QueryType.UPDATE);
        when(queryRequestLookupService.findDetailById(detail.id(), orgId))
                .thenReturn(Optional.of(detail));
        assertThatThrownBy(() -> tools.getQueryResult(detail.id()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void get_query_result_rejects_non_executed_status() {
        var detail = newQueryDetail(userId, QueryStatus.PENDING_REVIEW, QueryType.SELECT);
        when(queryRequestLookupService.findDetailById(detail.id(), orgId))
                .thenReturn(Optional.of(detail));
        assertThatThrownBy(() -> tools.getQueryResult(detail.id()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void submit_query_delegates_to_submission_service() {
        var queryId = UUID.randomUUID();
        var dsId = UUID.randomUUID();
        when(querySubmissionService.submit(any(QuerySubmissionService.SubmissionInput.class)))
                .thenReturn(new QuerySubmissionService.QuerySubmissionResult(queryId, QueryStatus.PENDING_AI));

        var result = tools.submitQuery(dsId, "SELECT 1", "demo");

        var captor = ArgumentCaptor.forClass(QuerySubmissionService.SubmissionInput.class);
        verify(querySubmissionService).submit(captor.capture());
        assertThat(captor.getValue().datasourceId()).isEqualTo(dsId);
        assertThat(captor.getValue().submitterUserId()).isEqualTo(userId);
        assertThat(captor.getValue().organizationId()).isEqualTo(orgId);
        assertThat(result.queryRequestId()).isEqualTo(queryId);
        assertThat(result.status()).isEqualTo("PENDING_AI");
    }

    @Test
    void cancel_query_delegates_to_lifecycle_service() {
        var queryId = UUID.randomUUID();
        var result = tools.cancelQuery(queryId);
        verify(queryLifecycleService).cancel(any(QueryLifecycleService.CancelQueryCommand.class));
        assertThat(result.status()).isEqualTo("CANCELLED");
    }

    @Test
    void get_datasource_schema_delegates() {
        var dsId = UUID.randomUUID();
        var schema = new DatabaseSchemaView(List.of());
        when(datasourceAdminService.introspectSchema(dsId, orgId, userId, false)).thenReturn(schema);
        var result = tools.getDatasourceSchema(dsId);
        assertThat(result).isSameAs(schema);
    }

    private void authenticateAs(UserRoleType role) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                new JwtClaims(userId, "user@example.com", role, orgId),
                null,
                "ROLE_" + role.name()));
    }

    private DatasourceView newDatasourceView() {
        return new DatasourceView(UUID.randomUUID(), orgId, "prod", DbType.POSTGRESQL,
                "localhost", 5432, "appdb", "app", SslMode.DISABLE,
                10, 10000, true, true, null, false, null, null, null, true, Instant.now());
    }

    private QueryDetailView newQueryDetail(UUID submitter, QueryStatus status, QueryType type) {
        return new QueryDetailView(UUID.randomUUID(), UUID.randomUUID(), "prod", orgId,
                submitter, "u@e.c", "User",
                "SELECT 1", type, status, "demo",
                null, 0L, 12, null,
                "plan", 24, List.of(), Instant.now(), Instant.now());
    }
}
