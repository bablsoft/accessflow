package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.QueryTemplateFilter;
import com.bablsoft.accessflow.workflow.api.QueryTemplateService;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import com.bablsoft.accessflow.workflow.internal.web.model.CreateQueryTemplateRequest;
import com.bablsoft.accessflow.workflow.internal.web.model.QueryTemplatePageResponse;
import com.bablsoft.accessflow.workflow.internal.web.model.QueryTemplateResponse;
import com.bablsoft.accessflow.workflow.internal.web.model.UpdateQueryTemplateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/query-templates")
@Tag(name = "Query Templates", description = "Saved SQL snippets analysts can load into /editor")
@RequiredArgsConstructor
@Slf4j
class QueryTemplateController {

    private final QueryTemplateService queryTemplateService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List query templates visible to the caller (paginated)")
    @ApiResponse(responseCode = "200", description = "Page of templates the caller may read "
            + "(their PRIVATE templates plus every TEAM template in the org)")
    QueryTemplatePageResponse list(
            @Parameter(description = "Filter by pinned datasource id")
            @RequestParam(required = false) UUID datasourceId,
            @Parameter(description = "Filter by a single tag (case-sensitive exact match)")
            @RequestParam(required = false) String tag,
            @Parameter(description = "Filter to only PRIVATE or TEAM templates")
            @RequestParam(required = false) QueryTemplateVisibility visibility,
            @Parameter(description = "Free-text search on name or description (case-insensitive)")
            @RequestParam(required = false) String q,
            Authentication authentication,
            Pageable pageable) {
        var caller = currentClaims(authentication);
        var filter = new QueryTemplateFilter(datasourceId, tag, visibility, q);
        var page = queryTemplateService.list(caller.organizationId(), caller.userId(), filter,
                        SpringPageableAdapter.toPageRequest(pageable))
                .map(view -> QueryTemplateResponse.from(view, caller.userId()));
        return QueryTemplatePageResponse.from(page);
    }

    @PostMapping
    @Operation(summary = "Create a new query template owned by the caller")
    @ApiResponse(responseCode = "201", description = "Template created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "409", description = "Caller already owns a template with this name")
    ResponseEntity<QueryTemplateResponse> create(
            @Valid @RequestBody CreateQueryTemplateRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new QueryTemplateService.CreateQueryTemplateCommand(
                caller.organizationId(),
                caller.userId(),
                request.datasourceId(),
                request.name(),
                request.body(),
                request.description(),
                request.tags(),
                request.visibility());
        var created = queryTemplateService.create(command);
        recordAudit(AuditAction.QUERY_TEMPLATE_CREATED, created.id(), caller, auditContext,
                Map.of("name", created.name(),
                        "visibility", created.visibility().name()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(QueryTemplateResponse.from(created, caller.userId()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a query template by id (subject to visibility rules)")
    @ApiResponse(responseCode = "200", description = "Template details")
    @ApiResponse(responseCode = "404", description = "Template not found or not visible to the caller")
    QueryTemplateResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        var view = queryTemplateService.get(id, caller.organizationId(), caller.userId());
        return QueryTemplateResponse.from(view, caller.userId());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a query template (owner only)")
    @ApiResponse(responseCode = "200", description = "Template updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller can see the template but is not its owner")
    @ApiResponse(responseCode = "404", description = "Template not found or not visible to the caller")
    @ApiResponse(responseCode = "409", description = "Owner already has another template with this name")
    QueryTemplateResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateQueryTemplateRequest request,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var command = new QueryTemplateService.UpdateQueryTemplateCommand(
                request.datasourceId(),
                request.name(),
                request.body(),
                request.description(),
                request.tags(),
                request.visibility());
        var updated = queryTemplateService.update(id, caller.organizationId(), caller.userId(), command);
        recordAudit(AuditAction.QUERY_TEMPLATE_UPDATED, id, caller, auditContext,
                Map.of("name", updated.name(),
                        "visibility", updated.visibility().name()));
        return QueryTemplateResponse.from(updated, caller.userId());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a query template (owner only)")
    @ApiResponse(responseCode = "204", description = "Template deleted")
    @ApiResponse(responseCode = "403", description = "Caller can see the template but is not its owner")
    @ApiResponse(responseCode = "404", description = "Template not found or not visible to the caller")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        queryTemplateService.delete(id, caller.organizationId(), caller.userId());
        recordAudit(AuditAction.QUERY_TEMPLATE_DELETED, id, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.QUERY_TEMPLATE,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on query_template {}", action, resourceId, ex);
        }
    }
}
