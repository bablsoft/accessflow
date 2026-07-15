package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.access.api.AccessGrantLookupService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.QueryCsvExportService;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService.CancelQueryCommand;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService.ExecuteQueryCommand;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService.ReanalyzeQueryCommand;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingDecisionService;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/queries")
@Tag(name = "Queries", description = "Query lifecycle read & control endpoints")
@RequiredArgsConstructor
@Slf4j
class QueryReadController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_RESULT_PAGE_SIZE = 500;

    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryLifecycleService queryLifecycleService;
    private final QueryResultPersistenceService queryResultPersistenceService;
    private final QueryCsvExportService queryCsvExportService;
    private final RoutingDecisionService routingDecisionService;
    private final AccessGrantLookupService accessGrantLookupService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    @GetMapping
    @Operation(summary = "List query requests in the caller's organization")
    @ApiResponse(responseCode = "200", description = "Page of query requests, ordered by createdAt DESC")
    QueryListPageResponse list(
            @Parameter(description = "Filter by status enum value")
            @RequestParam(required = false) QueryStatus status,
            @Parameter(description = "Filter by datasource id")
            @RequestParam(required = false) UUID datasourceId,
            @Parameter(description = "Filter by submitter user id (admin-only override)")
            @RequestParam(required = false) UUID submittedBy,
            @Parameter(description = "Inclusive lower bound on createdAt")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Exclusive upper bound on createdAt")
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Filter by query_type")
            @RequestParam(required = false) QueryType queryType,
            Authentication authentication,
            Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new BadQueryListException(
                    "Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        var caller = (JwtClaims) authentication.getPrincipal();
        var filter = buildFilter(caller, status, datasourceId, submittedBy, queryType, from, to);
        var page = queryRequestLookupService.findForOrganization(filter,
                        SpringPageableAdapter.toPageRequest(pageable))
                .map(QueryListItem::from);
        return QueryListPageResponse.from(page);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @Operation(summary = "Export query requests as CSV (same filter set as GET /queries)")
    @ApiResponse(responseCode = "200", description = "CSV body of query rows")
    ResponseEntity<byte[]> exportCsv(
            @Parameter(description = "Filter by status enum value")
            @RequestParam(required = false) QueryStatus status,
            @Parameter(description = "Filter by datasource id")
            @RequestParam(required = false) UUID datasourceId,
            @Parameter(description = "Filter by submitter user id (admin-only override)")
            @RequestParam(required = false) UUID submittedBy,
            @Parameter(description = "Inclusive lower bound on createdAt")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Exclusive upper bound on createdAt")
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Filter by query_type")
            @RequestParam(required = false) QueryType queryType,
            Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var filter = buildFilter(caller, status, datasourceId, submittedBy, queryType, from, to);
        var export = queryCsvExportService.exportQueries(filter);

        var headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + export.filename() + "\"");
        if (export.truncated()) {
            headers.add("X-AccessFlow-Export-Truncated", "true");
        }
        return ResponseEntity.ok().headers(headers).body(export.body());
    }

    private static QueryListFilter buildFilter(JwtClaims caller, QueryStatus status,
                                               UUID datasourceId, UUID submittedBy,
                                               QueryType queryType, Instant from, Instant to) {
        var effectiveSubmitter = caller.has(Permission.QUERY_ADMIN) ? submittedBy : caller.userId();
        return new QueryListFilter(caller.organizationId(), effectiveSubmitter, datasourceId,
                status, queryType, from, to);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one query request, including its AI analysis")
    @ApiResponse(responseCode = "200", description = "Query detail")
    @ApiResponse(responseCode = "403", description = "Caller is not the submitter, a reviewer, or an admin")
    @ApiResponse(responseCode = "404", description = "Query not found in caller's organization")
    QueryDetailResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var detail = queryRequestLookupService.findDetailById(id, caller.organizationId())
                .orElseThrow(() -> new QueryRequestNotFoundException(id));
        // Per docs/07-security.md, QUERY_VIEW_ALL holders (system REVIEWER/ADMIN) may read
        // any query; everyone else only their own rows.
        if (!caller.has(Permission.QUERY_VIEW_ALL)
                && !detail.submittedByUserId().equals(caller.userId())) {
            throw new QueryRequestNotFoundException(id);
        }
        var matchedPolicy = routingDecisionService.findMatchedPolicy(id).orElse(null);
        var approvingGrant = detail.approvedByGrantId() == null ? null
                : accessGrantLookupService.findGrant(detail.approvedByGrantId()).orElse(null);
        return QueryDetailResponse.from(detail, matchedPolicy, approvingGrant);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending query (submitter only)")
    @ApiResponse(responseCode = "204", description = "Query cancelled")
    @ApiResponse(responseCode = "403", description = "Caller is not the submitter")
    @ApiResponse(responseCode = "404", description = "Query not found")
    @ApiResponse(responseCode = "409", description = "Query is no longer cancellable")
    ResponseEntity<Void> cancel(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = (JwtClaims) authentication.getPrincipal();
        queryLifecycleService.cancel(new CancelQueryCommand(id, caller.userId(),
                caller.organizationId()));
        recordCancelAudit(caller, id, auditContext);
        return ResponseEntity.noContent().build();
    }

    private void recordCancelAudit(JwtClaims caller, UUID queryId,
                                   RequestAuditContext auditContext) {
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.QUERY_CANCELLED,
                    AuditResourceType.QUERY_REQUEST,
                    queryId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for QUERY_CANCELLED on query {}", queryId, ex);
        }
    }

    @PostMapping("/{id}/reanalyze")
    @PreAuthorize("hasAuthority('PERM_QUERY_REVIEW')")
    @Operation(summary = "Re-run AI analysis on a query whose previous AI analysis failed")
    @ApiResponse(responseCode = "202", description = "Re-analysis accepted; runs asynchronously")
    @ApiResponse(responseCode = "403", description = "Caller is not a reviewer or admin")
    @ApiResponse(responseCode = "404", description = "Query not found in caller's organization")
    @ApiResponse(responseCode = "409", description = "Query is not eligible for re-analysis "
            + "(wrong status or previous analysis did not fail)")
    ResponseEntity<Void> reanalyze(@PathVariable UUID id, Authentication authentication,
                                   RequestAuditContext auditContext) {
        var caller = (JwtClaims) authentication.getPrincipal();
        queryLifecycleService.reanalyze(new ReanalyzeQueryCommand(id, caller.userId(),
                caller.organizationId()));
        recordReanalyzeAudit(caller, id, auditContext);
        return ResponseEntity.accepted().build();
    }

    private void recordReanalyzeAudit(JwtClaims caller, UUID queryId,
                                      RequestAuditContext auditContext) {
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.QUERY_AI_REANALYZE_REQUESTED,
                    AuditResourceType.QUERY_REQUEST,
                    queryId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for QUERY_AI_REANALYZE_REQUESTED on query {}",
                    queryId, ex);
        }
    }

    @PostMapping("/{id}/execute")
    @Operation(summary = "Manually execute an approved query")
    @ApiResponse(responseCode = "202", description = "Execution accepted; status is EXECUTED or FAILED")
    @ApiResponse(responseCode = "403", description = "Caller is not the submitter and not an admin")
    @ApiResponse(responseCode = "404", description = "Query not found")
    @ApiResponse(responseCode = "409", description = "Query is not in APPROVED status")
    ResponseEntity<ExecuteQueryResponse> execute(@PathVariable UUID id,
                                                 Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var outcome = queryLifecycleService.execute(new ExecuteQueryCommand(id, caller.userId(),
                caller.organizationId(), caller.has(Permission.QUERY_ADMIN)));
        return ResponseEntity.accepted().body(new ExecuteQueryResponse(
                outcome.queryRequestId(), outcome.status(), outcome.rowsAffected(),
                outcome.durationMs()));
    }

    @GetMapping("/{id}/results")
    @Operation(summary = "Paginated SELECT results (only available after a successful EXECUTED transition)")
    @ApiResponse(responseCode = "200", description = "Page of result rows")
    @ApiResponse(responseCode = "404", description = "Query not found, or no stored results yet")
    @ApiResponse(responseCode = "422", description = "Query is not a SELECT")
    QueryResultsPageResponse results(@PathVariable UUID id,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "100") int size,
                                     Authentication authentication) {
        if (size <= 0 || size > MAX_RESULT_PAGE_SIZE) {
            throw new BadQueryListException("size must be between 1 and " + MAX_RESULT_PAGE_SIZE);
        }
        if (page < 0) {
            throw new BadQueryListException("page must be >= 0");
        }
        var caller = (JwtClaims) authentication.getPrincipal();
        var detail = queryRequestLookupService.findDetailById(id, caller.organizationId())
                .orElseThrow(() -> new QueryRequestNotFoundException(id));
        if (!caller.has(Permission.QUERY_ADMIN)
                && !detail.submittedByUserId().equals(caller.userId())) {
            throw new QueryRequestNotFoundException(id);
        }
        if (detail.queryType() != QueryType.SELECT) {
            throw new ResultsNotAvailableException(id, "Query is not a SELECT");
        }
        var snapshot = queryResultPersistenceService.find(id)
                .orElseThrow(() -> new QueryRequestNotFoundException(id));
        return slice(snapshot, page, size);
    }

    @GetMapping("/{id}/diff")
    @Operation(summary = "Compare this query's execution outcome to its previous successful run "
            + "(rows affected, execution duration, result row count)")
    @ApiResponse(responseCode = "200", description = "Delta against the linked previous run")
    @ApiResponse(responseCode = "403", description = "Caller is not the submitter, a reviewer, or an admin")
    @ApiResponse(responseCode = "404", description = "Query not found, or no previous run is linked")
    QueryDiffResponse diff(@PathVariable UUID id, Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var current = queryRequestLookupService.findDetailById(id, caller.organizationId())
                .orElseThrow(() -> new QueryRequestNotFoundException(id));
        if (!caller.has(Permission.QUERY_VIEW_ALL)
                && !current.submittedByUserId().equals(caller.userId())) {
            throw new QueryRequestNotFoundException(id);
        }
        if (current.previousRunId() == null) {
            throw new QueryDiffNotAvailableException(id);
        }
        var previous = queryRequestLookupService
                .findDetailById(current.previousRunId(), caller.organizationId())
                .orElseThrow(() -> new QueryDiffNotAvailableException(id));

        Long rowsAffectedDelta = subtract(current.rowsAffected(), previous.rowsAffected());
        Integer executionMsDelta = subtract(current.durationMs(), previous.durationMs());
        Long rowCountDelta = null;
        if (current.queryType() == QueryType.SELECT && previous.queryType() == QueryType.SELECT) {
            var currentSnapshot = queryResultPersistenceService.find(current.id());
            var previousSnapshot = queryResultPersistenceService.find(previous.id());
            if (currentSnapshot.isPresent() && previousSnapshot.isPresent()) {
                rowCountDelta = currentSnapshot.get().rowCount() - previousSnapshot.get().rowCount();
            }
        }
        return new QueryDiffResponse(current.id(), previous.id(), rowsAffectedDelta,
                executionMsDelta, rowCountDelta);
    }

    private static Long subtract(Long a, Long b) {
        if (a == null || b == null) {
            return null;
        }
        return a - b;
    }

    private static Integer subtract(Integer a, Integer b) {
        if (a == null || b == null) {
            return null;
        }
        return a - b;
    }

    private QueryResultsPageResponse slice(
            QueryResultPersistenceService.QueryResultSnapshot snapshot, int page, int size) {
        try {
            var rowsArray = objectMapper.readTree(snapshot.rowsJson());
            int total = rowsArray.size();
            int from = Math.min(page * size, total);
            int to = Math.min(from + size, total);
            var sliced = objectMapper.createArrayNode();
            for (int i = from; i < to; i++) {
                sliced.add(rowsArray.get(i));
            }
            return new QueryResultsPageResponse(
                    snapshot.columnsJson(),
                    sliced.toString(),
                    snapshot.rowCount(),
                    snapshot.truncated(),
                    page,
                    size);
        } catch (RuntimeException ex) {
            log.error("Failed to slice stored results for query {}", snapshot.queryRequestId(), ex);
            throw ex;
        }
    }

    static final class BadQueryListException extends RuntimeException {
        BadQueryListException(String message) {
            super(message);
        }
    }

    static final class ResultsNotAvailableException extends RuntimeException {
        private final UUID queryRequestId;

        ResultsNotAvailableException(UUID queryRequestId, String reason) {
            super(reason);
            this.queryRequestId = queryRequestId;
        }

        UUID queryRequestId() {
            return queryRequestId;
        }
    }

    static final class QueryDiffNotAvailableException extends RuntimeException {
        private final UUID queryRequestId;

        QueryDiffNotAvailableException(UUID queryRequestId) {
            super("No previous run is linked to query " + queryRequestId);
            this.queryRequestId = queryRequestId;
        }

        UUID queryRequestId() {
            return queryRequestId;
        }
    }

    @ExceptionHandler(BadQueryListException.class)
    ResponseEntity<ProblemDetail> handleBadList(BadQueryListException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setProperty("error", "BAD_QUERY_LIST");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    @ExceptionHandler(ResultsNotAvailableException.class)
    ResponseEntity<ProblemDetail> handleResultsUnavailable(ResultsNotAvailableException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setProperty("error", "RESULTS_NOT_AVAILABLE");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(pd);
    }

    @ExceptionHandler(QueryDiffNotAvailableException.class)
    ResponseEntity<ProblemDetail> handleDiffUnavailable(QueryDiffNotAvailableException ex) {
        var detail = messageSource.getMessage("error.query_diff_no_previous_run", null,
                LocaleContextHolder.getLocale());
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        pd.setProperty("error", "QUERY_DIFF_NOT_AVAILABLE");
        pd.setProperty("queryRequestId", ex.queryRequestId().toString());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }
}
