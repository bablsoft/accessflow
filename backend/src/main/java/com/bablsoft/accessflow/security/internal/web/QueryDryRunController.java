package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.proxy.api.QueryDryRunService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hosts {@code POST /api/v1/queries/dry-run} in the security module — like the datasource
 * sample-rows endpoint, this delegates to a {@code proxy.api} service, and the security module is
 * the module that already depends on {@code proxy} (so the controller can also use {@link JwtClaims}
 * without introducing a module cycle).
 */
@RestController
@RequestMapping("/api/v1/queries")
@Tag(name = "Query Dry-Run", description = "Non-committing EXPLAIN plans and estimated impact")
@RequiredArgsConstructor
class QueryDryRunController {

    private final QueryDryRunService queryDryRunService;

    @PostMapping("/dry-run")
    @Operation(summary = "Return a non-committing execution plan and estimated row impact for the "
            + "SQL without executing or mutating data, applying the caller's row-security")
    @ApiResponse(responseCode = "200", description = "Dry-run plan, or a graceful "
            + "supported=false result for engines without a plan concept")
    @ApiResponse(responseCode = "403", description = "Caller lacks the capability or allow-list "
            + "entry for a referenced table")
    @ApiResponse(responseCode = "404", description = "Datasource not found or not accessible")
    @ApiResponse(responseCode = "422", description = "SQL could not be parsed, or the dry-run "
            + "failed against the customer database")
    QueryDryRunResponse dryRun(@Valid @RequestBody QueryDryRunRequestBody request,
                               Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        return QueryDryRunResponse.from(queryDryRunService.dryRun(
                request.datasourceId(),
                request.sql(),
                caller.userId(),
                caller.organizationId(),
                caller.has(Permission.QUERY_ADMIN)));
    }
}
