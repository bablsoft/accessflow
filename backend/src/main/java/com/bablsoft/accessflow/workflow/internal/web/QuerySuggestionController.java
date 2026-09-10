package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionRecomputeTrigger;
import com.bablsoft.accessflow.workflow.api.QuerySuggestionService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Automatic query suggestions for one datasource (#776).
 *
 * <p>No class-level {@code @PreAuthorize} on the read: like {@code QueryTemplateController},
 * authorization here is not an authority string but the service's visibility-plus-permission
 * filter, which is finer than any role could be. The recompute is an operator action and does carry
 * one.
 */
@RestController
@RequestMapping("/api/v1/datasources/{datasourceId}/query-suggestions")
@Tag(name = "Query Suggestions",
        description = "Draft queries mined from the organisation's own approved history")
@RequiredArgsConstructor
@Validated
class QuerySuggestionController {

    private final QuerySuggestionService querySuggestionService;
    private final QuerySuggestionRecomputeTrigger recomputeTrigger;

    @GetMapping
    @Operation(summary = "Ranked query suggestions the caller may run on this datasource")
    @ApiResponse(responseCode = "200",
            description = "The rail; empty when the caller holds no unexpired grant")
    @ApiResponse(responseCode = "400", description = "limit out of range")
    @ApiResponse(responseCode = "404", description = "Datasource not found or not accessible")
    QuerySuggestionListResponse list(
            @PathVariable UUID datasourceId,
            @Parameter(description = "Maximum suggestions to return")
            @RequestParam(required = false, defaultValue = "0")
            @Min(value = 0, message = "{validation.query_suggestions.limit.min}")
            @Max(value = 50, message = "{validation.query_suggestions.limit.max}") int limit,
            Authentication authentication) {
        var caller = currentClaims(authentication);
        return QuerySuggestionListResponse.from(querySuggestionService.findForViewer(
                datasourceId, caller.organizationId(), caller.userId(),
                caller.has(Permission.QUERY_ADMIN), limit));
    }

    @PostMapping("/recompute")
    @Operation(summary = "Rebuild this datasource's suggestions now (runs asynchronously)")
    @ApiResponse(responseCode = "202", description = "Recompute accepted")
    @ApiResponse(responseCode = "404", description = "Datasource not found or not accessible")
    @ApiResponse(responseCode = "409",
            description = "A recompute for this datasource is already running")
    @ApiResponse(responseCode = "500",
            description = "The cluster lock backing the recompute guard is unreachable")
    @PreAuthorize("hasAuthority('PERM_QUERY_ADMIN')")
    ResponseEntity<Void> recompute(@PathVariable UUID datasourceId,
                                   Authentication authentication) {
        var caller = currentClaims(authentication);
        recomputeTrigger.requestRecompute(datasourceId, caller.organizationId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
