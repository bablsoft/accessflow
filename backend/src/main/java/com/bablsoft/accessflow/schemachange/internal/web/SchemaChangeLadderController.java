package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaChangeLadderService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Schema Change Ladder",
        description = "Read-only pipeline and promotion-ladder views for schema change governance (epic #870)")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_SCHEMA_CHANGE_MANAGE')")
class SchemaChangeLadderController {

    private final SchemaChangeLadderService ladderService;

    @GetMapping("/schema-change-pipelines")
    @Operation(summary = "List the organization's pipelines with their environments in ladder order")
    @ApiResponse(responseCode = "200", description = "Pipelines")
    List<SchemaChangePipelineResponse> listPipelines(Authentication authentication) {
        var caller = claims(authentication);
        return ladderService.listPipelines(caller.organizationId()).stream()
                .map(SchemaChangePipelineResponse::from)
                .toList();
    }

    @GetMapping("/schema-change-sets/{id}/ladder")
    @Operation(summary = "Preview a change set's promotion ladder with the reason each blocked rung is blocked")
    @ApiResponse(responseCode = "200", description = "Ladder")
    @ApiResponse(responseCode = "404", description = "Change set not found")
    SchemaChangeLadderResponse ladder(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return SchemaChangeLadderResponse.from(ladderService.ladder(caller.organizationId(), id));
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
