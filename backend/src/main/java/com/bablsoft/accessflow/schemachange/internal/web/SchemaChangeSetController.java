package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeStatementFinding;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingRenderer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.function.Function;

@RestController
@RequestMapping("/api/v1/schema-change-sets")
@Tag(name = "Schema Change Sets",
        description = "Author governed DDL change sets under a deployment pipeline (epic #870)")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_SCHEMA_CHANGE_MANAGE')")
class SchemaChangeSetController {

    private final SchemaChangeSetService changeSetService;
    private final SqlReviewFindingRenderer findingRenderer;

    @GetMapping
    @Operation(summary = "List the organization's schema change sets, newest first")
    @ApiResponse(responseCode = "200", description = "Page of change sets")
    SchemaChangeSetPageResponse list(@RequestParam(name = "pipeline_id", required = false) UUID pipelineId,
                                     @RequestParam(name = "status", required = false) SchemaChangeSetStatus status,
                                     Authentication authentication, Pageable pageable) {
        var caller = claims(authentication);
        var page = changeSetService.list(caller.organizationId(),
                new SchemaChangeSetListFilter(pipelineId, status), SpringPageableAdapter.toPageRequest(pageable));
        return SchemaChangeSetPageResponse.from(page, renderer());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a schema change set with its ordered statements")
    @ApiResponse(responseCode = "200", description = "Change set")
    @ApiResponse(responseCode = "404", description = "Change set not found")
    SchemaChangeSetResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return SchemaChangeSetResponse.from(changeSetService.get(caller.organizationId(), id), renderer());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a schema change set, optionally with its initial statements")
    @ApiResponse(responseCode = "201", description = "Change set created; WARN findings under reviewWarnings")
    @ApiResponse(responseCode = "400", description = "Validation error or statement cap exceeded")
    @ApiResponse(responseCode = "404", description = "Pipeline not found in this organization")
    @ApiResponse(responseCode = "409", description = "Name already used under the pipeline, "
            + "or no environment of the pipeline binds a datasource")
    @ApiResponse(responseCode = "422", description = "A statement failed the validation gate")
    SchemaChangeSetResponse create(@Valid @RequestBody CreateSchemaChangeSetRequest body,
                                   Authentication authentication) {
        var caller = claims(authentication);
        return SchemaChangeSetResponse.from(
                changeSetService.create(caller.organizationId(), caller.userId(), body.toCommand()), renderer());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a schema change set's name, description or status "
            + "(null fields stay unchanged; status may only move to ARCHIVED)")
    @ApiResponse(responseCode = "200", description = "Change set updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Change set not found")
    @ApiResponse(responseCode = "409", description = "Name already used under the pipeline, or a status "
            + "transition other than to ARCHIVED")
    SchemaChangeSetResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateSchemaChangeSetRequest body,
                                   Authentication authentication) {
        var caller = claims(authentication);
        return SchemaChangeSetResponse.from(
                changeSetService.update(caller.organizationId(), caller.userId(), id, body.toCommand()), renderer());
    }

    @PutMapping("/{id}/statements")
    @Operation(summary = "Replace the whole ordered statement list of a schema change set")
    @ApiResponse(responseCode = "200", description = "Statements replaced; WARN findings under reviewWarnings")
    @ApiResponse(responseCode = "400", description = "Validation error or statement cap exceeded")
    @ApiResponse(responseCode = "404", description = "Change set not found")
    @ApiResponse(responseCode = "409", description = "Change set frozen by a promotion, archived, "
            + "or no environment of the pipeline binds a datasource")
    @ApiResponse(responseCode = "422", description = "A statement failed the validation gate")
    SchemaChangeSetResponse replaceStatements(@PathVariable UUID id,
                                              @Valid @RequestBody ReplaceSchemaChangeSetStatementsRequest body,
                                              Authentication authentication) {
        var caller = claims(authentication);
        return SchemaChangeSetResponse.from(
                changeSetService.replaceStatements(caller.organizationId(), caller.userId(), id,
                        body.toInputs()), renderer());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a schema change set and its statements")
    @ApiResponse(responseCode = "204", description = "Change set deleted")
    @ApiResponse(responseCode = "404", description = "Change set not found")
    @ApiResponse(responseCode = "409", description = "Change set frozen by a promotion")
    void delete(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        changeSetService.delete(caller.organizationId(), caller.userId(), id);
    }

    private Function<SchemaChangeStatementFinding, String> renderer() {
        var locale = LocaleContextHolder.getLocale();
        return f -> findingRenderer.message(f.finding(), locale);
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
