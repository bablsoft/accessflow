package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService;
import com.bablsoft.accessflow.workflow.api.QuerySubmissionService.SubmissionInput;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

@RestController
@RequestMapping("/api/v1/queries")
@Tag(name = "Queries", description = "Query submission and lifecycle endpoints")
@RequiredArgsConstructor
@Slf4j
class QuerySubmissionController {

    private final QuerySubmissionService querySubmissionService;
    private final AuditLogService auditLogService;

    @PostMapping
    @Operation(summary = "Submit a query for AI analysis and (eventually) human review")
    @ApiResponse(responseCode = "202", description = "Query accepted; AI analysis runs asynchronously")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller has no permission for this datasource or query type")
    @ApiResponse(responseCode = "404", description = "Datasource not found or not accessible")
    @ApiResponse(responseCode = "422", description = "SQL could not be parsed or query type is not supported")
    ResponseEntity<SubmitQueryResponse> submit(@Valid @RequestBody SubmitQueryRequestBody body,
                                               Authentication authentication,
                                               HttpServletRequest httpRequest) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var result = querySubmissionService.submit(new SubmissionInput(
                body.datasourceId(),
                body.sql(),
                body.justification(),
                caller.userId(),
                caller.organizationId(),
                caller.role() == UserRoleType.ADMIN));
        recordAudit(caller, result.id(), body, httpRequest);
        return ResponseEntity.accepted().body(new SubmitQueryResponse(
                result.id(), result.status(), null, null, null));
    }

    private void recordAudit(JwtClaims caller, java.util.UUID queryId,
                             SubmitQueryRequestBody body, HttpServletRequest httpRequest) {
        try {
            var context = RequestAuditContext.from(httpRequest);
            var metadata = new HashMap<String, Object>();
            metadata.put("datasource_id", body.datasourceId().toString());
            auditLogService.record(new AuditEntry(
                    AuditAction.QUERY_SUBMITTED,
                    AuditResourceType.QUERY_REQUEST,
                    queryId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    context.ipAddress(),
                    context.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for QUERY_SUBMITTED on query {}", queryId, ex);
        }
    }
}
