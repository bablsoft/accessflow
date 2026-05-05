package com.partqam.accessflow.workflow.internal.web;

import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.security.api.JwtClaims;
import com.partqam.accessflow.workflow.api.QuerySubmissionService;
import com.partqam.accessflow.workflow.api.QuerySubmissionService.SubmissionInput;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/queries")
@Tag(name = "Queries", description = "Query submission and lifecycle endpoints")
@RequiredArgsConstructor
class QuerySubmissionController {

    private final QuerySubmissionService querySubmissionService;

    @PostMapping
    @Operation(summary = "Submit a query for AI analysis and (eventually) human review")
    @ApiResponse(responseCode = "202", description = "Query accepted; AI analysis runs asynchronously")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller has no permission for this datasource or query type")
    @ApiResponse(responseCode = "404", description = "Datasource not found or not accessible")
    @ApiResponse(responseCode = "422", description = "SQL could not be parsed or query type is not supported")
    ResponseEntity<SubmitQueryResponse> submit(@Valid @RequestBody SubmitQueryRequestBody body,
                                               Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var result = querySubmissionService.submit(new SubmissionInput(
                body.datasourceId(),
                body.sql(),
                body.justification(),
                caller.userId(),
                caller.organizationId(),
                caller.role() == UserRoleType.ADMIN));
        return ResponseEntity.accepted().body(new SubmitQueryResponse(
                result.id(), result.status(), null, null, null));
    }
}
