package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.BreakGlassService;
import com.bablsoft.accessflow.workflow.api.BreakGlassService.BreakGlassInput;
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
 * Break-glass / emergency access (AF-385). Executes immediately through the proxy guards, bypassing
 * pre-approval. The response is synchronous (200) — execution has already run — unlike the normal
 * submit endpoint's 202. The prominent {@code QUERY_BREAK_GLASS_EXECUTED} audit row is written
 * inside the execution path, so this controller does not double-write audit.
 */
@RestController
@RequestMapping("/api/v1/queries/break-glass")
@Tag(name = "Break-glass", description = "Emergency access — immediate execution with mandatory retro-review")
@RequiredArgsConstructor
class BreakGlassController {

    private final BreakGlassService breakGlassService;

    @PostMapping
    @Operation(summary = "Execute an emergency (break-glass) query immediately, bypassing approval")
    @ApiResponse(responseCode = "200", description = "Query executed; a mandatory retro-review was opened")
    @ApiResponse(responseCode = "400", description = "Validation error (justification required)")
    @ApiResponse(responseCode = "403", description = "Caller has no break-glass grant for this datasource")
    @ApiResponse(responseCode = "404", description = "Datasource not found or not accessible")
    @ApiResponse(responseCode = "422", description = "SQL could not be parsed or query type is not supported")
    BreakGlassExecuteResponse breakGlass(@Valid @RequestBody BreakGlassSubmitRequest body,
                                         Authentication authentication,
                                         RequestAuditContext auditContext) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var result = breakGlassService.breakGlassExecute(new BreakGlassInput(
                body.datasourceId(),
                body.sql(),
                body.justification(),
                caller.userId(),
                caller.organizationId(),
                caller.role() == UserRoleType.ADMIN,
                auditContext.ipAddress(),
                auditContext.userAgent()));
        return BreakGlassExecuteResponse.from(result);
    }
}
