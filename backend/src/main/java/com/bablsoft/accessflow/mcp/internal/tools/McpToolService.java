package com.bablsoft.accessflow.mcp.internal.tools;

import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpDatasourceSummary;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpPage;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpQueryDetail;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpQueryResult;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpQuerySubmission;
import com.bablsoft.accessflow.mcp.internal.tools.dto.McpQuerySummary;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * MCP tools exposing the AccessFlow read + workflow surface to AI agents. Every tool resolves
 * the calling user from {@link McpCurrentUser} and delegates to existing {@code core.api} /
 * {@code workflow.api} services — all authorization, permission, and state-machine guards stay
 * inside those services.
 */
@Service
@RequiredArgsConstructor
public class McpToolService {

    private static final int MAX_PAGE_SIZE = 100;

    private final McpCurrentUser currentUser;
    private final DatasourceAdminService datasourceAdminService;
    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryResultPersistenceService queryResultPersistenceService;
    private final QuerySubmissionService querySubmissionService;
    private final QueryLifecycleService queryLifecycleService;

    @Tool(name = "list_datasources",
            description = "List the datasources the current user has permission to query.")
    public McpPage<McpDatasourceSummary> listDatasources(
            @ToolParam(required = false, description = "Zero-based page number (default 0).") Integer page,
            @ToolParam(required = false, description = "Page size (default 20, max 100).") Integer size) {
        var claims = currentUser.requireClaims();
        var pageRequest = pageRequest(page, size);
        var result = currentUser.isAdmin()
                ? datasourceAdminService.listForAdmin(claims.organizationId(), pageRequest)
                : datasourceAdminService.listForUser(claims.organizationId(), claims.userId(), pageRequest);
        return McpPage.from(result, McpDatasourceSummary::from);
    }

    @Tool(name = "get_datasource_schema",
            description = "Introspect the schemas, tables, and columns of a datasource so the "
                    + "agent can write valid SQL against it.")
    public com.bablsoft.accessflow.core.api.DatabaseSchemaView getDatasourceSchema(
            @ToolParam(description = "Datasource UUID.") UUID datasourceId) {
        var claims = currentUser.requireClaims();
        return datasourceAdminService.introspectSchema(
                datasourceId, claims.organizationId(), claims.userId(), currentUser.isAdmin());
    }

    @Tool(name = "list_my_queries",
            description = "List the calling user's submitted queries, optionally filtered by status, "
                    + "datasource, or query type.")
    public McpPage<McpQuerySummary> listMyQueries(
            @ToolParam(required = false, description = "Filter by QueryStatus (e.g. PENDING_REVIEW, EXECUTED).") String status,
            @ToolParam(required = false, description = "Filter by datasource UUID.") UUID datasourceId,
            @ToolParam(required = false, description = "Filter by QueryType (SELECT, INSERT, UPDATE, DELETE, DDL).") String queryType,
            @ToolParam(required = false, description = "Zero-based page number (default 0).") Integer page,
            @ToolParam(required = false, description = "Page size (default 20, max 100).") Integer size) {
        var claims = currentUser.requireClaims();
        var filter = new QueryListFilter(
                claims.organizationId(),
                claims.userId(),
                datasourceId,
                parseEnum(QueryStatus.class, status),
                parseEnum(QueryType.class, queryType),
                null,
                null);
        var result = queryRequestLookupService.findForOrganization(filter, pageRequest(page, size));
        return McpPage.from(result, McpQuerySummary::from);
    }

    @Tool(name = "get_query_status",
            description = "Get the current status, AI risk, and review decisions for a query the "
                    + "calling user submitted (or any query when the caller is an admin).")
    public McpQueryDetail getQueryStatus(
            @ToolParam(description = "Query request UUID.") UUID queryId) {
        var claims = currentUser.requireClaims();
        var detail = queryRequestLookupService.findDetailById(queryId, claims.organizationId())
                .orElseThrow(() -> new com.bablsoft.accessflow.core.api.QueryRequestNotFoundException(queryId));
        if (!currentUser.isAdmin() && !detail.submittedByUserId().equals(claims.userId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You can only view queries you submitted");
        }
        return McpQueryDetail.from(detail);
    }

    @Tool(name = "get_query_result",
            description = "Retrieve the persisted SELECT results for an EXECUTED query. Returns the "
                    + "columns and rows as JSON strings — clients can parse them into structured data.")
    public McpQueryResult getQueryResult(
            @ToolParam(description = "Query request UUID.") UUID queryId) {
        var claims = currentUser.requireClaims();
        var detail = queryRequestLookupService.findDetailById(queryId, claims.organizationId())
                .orElseThrow(() -> new com.bablsoft.accessflow.core.api.QueryRequestNotFoundException(queryId));
        if (!currentUser.isAdmin() && !detail.submittedByUserId().equals(claims.userId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You can only view results for queries you submitted");
        }
        if (detail.queryType() != QueryType.SELECT) {
            throw new IllegalStateException("Query is not a SELECT — no results to return");
        }
        if (detail.status() != QueryStatus.EXECUTED) {
            throw new IllegalStateException("Query is not in EXECUTED status (current: "
                    + detail.status() + ")");
        }
        var snapshot = queryResultPersistenceService.find(queryId)
                .orElseThrow(() -> new com.bablsoft.accessflow.core.api.QueryRequestNotFoundException(queryId));
        return new McpQueryResult(
                snapshot.columnsJson(),
                snapshot.rowsJson(),
                snapshot.rowCount(),
                snapshot.truncated(),
                snapshot.durationMs()
        );
    }

    @Tool(name = "submit_query",
            description = "Submit a SQL query against an AccessFlow-managed datasource. Returns "
                    + "the new query request id and its initial status. The query then proceeds "
                    + "through AI analysis and the datasource's configured review workflow before "
                    + "executing.")
    public McpQuerySubmission submitQuery(
            @ToolParam(description = "Target datasource UUID (must be one returned by list_datasources).") UUID datasourceId,
            @ToolParam(description = "The SQL statement to submit.") String sql,
            @ToolParam(required = false, description = "Optional justification shown to reviewers.") String justification) {
        var claims = currentUser.requireClaims();
        var input = new QuerySubmissionService.SubmissionInput(
                datasourceId, sql, justification,
                claims.userId(), claims.organizationId(), currentUser.isAdmin());
        var result = querySubmissionService.submit(input);
        return new McpQuerySubmission(result.id(), result.status().name());
    }

    @Tool(name = "cancel_query",
            description = "Cancel a query that is still pending AI analysis or review. Only the "
                    + "original submitter can cancel.")
    public McpQuerySubmission cancelQuery(
            @ToolParam(description = "Query request UUID.") UUID queryId) {
        var claims = currentUser.requireClaims();
        queryLifecycleService.cancel(new QueryLifecycleService.CancelQueryCommand(
                queryId, claims.userId(), claims.organizationId()));
        return new McpQuerySubmission(queryId, QueryStatus.CANCELLED.name());
    }

    private static PageRequest pageRequest(Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(p, s);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
