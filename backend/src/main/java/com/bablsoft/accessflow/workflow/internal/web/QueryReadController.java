package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryListFilter;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.QueryCsvExportService;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService.CancelQueryCommand;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService.ExecuteQueryCommand;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
    private final ObjectMapper objectMapper;

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
        var effectiveSubmitter = caller.role() == UserRoleType.ADMIN ? submittedBy : caller.userId();
        return new QueryListFilter(caller.organizationId(), effectiveSubmitter, datasourceId,
                status, queryType, from, to);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one query request, including its AI analysis")
    @ApiResponse(responseCode = "200", description = "Query detail")
    @ApiResponse(responseCode = "403", description = "Caller is not the submitter and not an admin")
    @ApiResponse(responseCode = "404", description = "Query not found in caller's organization")
    QueryDetailResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var detail = queryRequestLookupService.findDetailById(id, caller.organizationId())
                .orElseThrow(() -> new QueryRequestNotFoundException(id));
        if (caller.role() != UserRoleType.ADMIN
                && !detail.submittedByUserId().equals(caller.userId())) {
            throw new QueryRequestNotFoundException(id);
        }
        return QueryDetailResponse.from(detail);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel a pending query (submitter only)")
    @ApiResponse(responseCode = "204", description = "Query cancelled")
    @ApiResponse(responseCode = "403", description = "Caller is not the submitter")
    @ApiResponse(responseCode = "404", description = "Query not found")
    @ApiResponse(responseCode = "409", description = "Query is no longer cancellable")
    ResponseEntity<Void> cancel(@PathVariable UUID id, Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        queryLifecycleService.cancel(new CancelQueryCommand(id, caller.userId(),
                caller.organizationId()));
        return ResponseEntity.noContent().build();
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
                caller.organizationId(), caller.role() == UserRoleType.ADMIN));
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
        if (caller.role() != UserRoleType.ADMIN
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
}
