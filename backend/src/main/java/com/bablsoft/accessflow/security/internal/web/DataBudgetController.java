package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.DataBudgetAdminService;
import com.bablsoft.accessflow.core.api.DataBudgetCommand;
import com.bablsoft.accessflow.core.api.DataBudgetView;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.DataBudgetListResponse;
import com.bablsoft.accessflow.security.internal.web.model.DataBudgetRequest;
import com.bablsoft.accessflow.security.internal.web.model.DataBudgetResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/datasources/{datasourceId}/data-budgets")
@Tag(name = "Data budgets",
        description = "Per-user data-volume budgets over a rolling window on a datasource (#942)")
@PreAuthorize("hasAuthority('PERM_DATA_BUDGET_MANAGE')")
@RequiredArgsConstructor
@Slf4j
class DataBudgetController {

    private final DataBudgetAdminService dataBudgetAdminService;
    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "List data budgets configured on a datasource")
    @ApiResponse(responseCode = "200", description = "List of data budgets")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    DataBudgetListResponse list(@PathVariable UUID datasourceId, Authentication authentication) {
        var caller = currentClaims(authentication);
        return new DataBudgetListResponse(dataBudgetAdminService
                .listForDatasource(datasourceId, caller.organizationId()).stream()
                .map(DataBudgetResponse::from)
                .toList());
    }

    @PostMapping
    @Operation(summary = "Create a data budget on a datasource")
    @ApiResponse(responseCode = "201", description = "Data budget created")
    @ApiResponse(responseCode = "400", description = "Bean Validation failure")
    @ApiResponse(responseCode = "404", description = "Datasource not found")
    @ApiResponse(responseCode = "422", description = "No limit, a limit or window out of range, or an invalid applies-to target")
    ResponseEntity<DataBudgetResponse> create(@PathVariable UUID datasourceId,
                                              @Valid @RequestBody DataBudgetRequest request,
                                              Authentication authentication,
                                              RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var view = dataBudgetAdminService.create(datasourceId, caller.organizationId(),
                toCommand(request));
        recordAudit(AuditAction.DATA_BUDGET_CREATED, view, caller, auditContext);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{budgetId}")
                .buildAndExpand(view.id())
                .toUri();
        return ResponseEntity.created(location).body(DataBudgetResponse.from(view));
    }

    @PutMapping("/{budgetId}")
    @Operation(summary = "Update a data budget")
    @ApiResponse(responseCode = "200", description = "Data budget updated")
    @ApiResponse(responseCode = "400", description = "Bean Validation failure")
    @ApiResponse(responseCode = "404", description = "Datasource or budget not found")
    @ApiResponse(responseCode = "422", description = "No limit, a limit or window out of range, or an invalid applies-to target")
    DataBudgetResponse update(@PathVariable UUID datasourceId,
                              @PathVariable UUID budgetId,
                              @Valid @RequestBody DataBudgetRequest request,
                              Authentication authentication,
                              RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var view = dataBudgetAdminService.update(budgetId, datasourceId, caller.organizationId(),
                toCommand(request));
        recordAudit(AuditAction.DATA_BUDGET_UPDATED, view, caller, auditContext);
        return DataBudgetResponse.from(view);
    }

    @DeleteMapping("/{budgetId}")
    @Operation(summary = "Delete a data budget")
    @ApiResponse(responseCode = "204", description = "Data budget deleted")
    @ApiResponse(responseCode = "404", description = "Datasource or budget not found")
    ResponseEntity<Void> delete(@PathVariable UUID datasourceId,
                                @PathVariable UUID budgetId,
                                Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        dataBudgetAdminService.delete(budgetId, datasourceId, caller.organizationId());
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", datasourceId.toString());
        recordAudit(AuditAction.DATA_BUDGET_DELETED, budgetId, caller, auditContext, metadata);
        return ResponseEntity.noContent().build();
    }

    private static DataBudgetCommand toCommand(DataBudgetRequest request) {
        return new DataBudgetCommand(request.name(), request.maxRows(), request.maxBytes(),
                request.windowMinutes(), request.breachAction(), request.warnThresholdPercent(),
                request.appliesToRoles(), request.appliesToGroupIds(), request.appliesToUserIds(),
                request.enabled());
    }

    private void recordAudit(AuditAction action, DataBudgetView view, JwtClaims caller,
                             RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("datasource_id", view.datasourceId().toString());
        metadata.put("name", view.name());
        if (view.maxRows() != null) {
            metadata.put("max_rows", view.maxRows());
        }
        if (view.maxBytes() != null) {
            metadata.put("max_bytes", view.maxBytes());
        }
        metadata.put("window_minutes", view.windowMinutes());
        metadata.put("breach_action", view.breachAction().name());
        metadata.put("enabled", view.enabled());
        recordAudit(action, view.id(), caller, auditContext, metadata);
    }

    private void recordAudit(AuditAction action, UUID budgetId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(action, AuditResourceType.DATA_BUDGET, budgetId,
                    caller.organizationId(), caller.userId(), new HashMap<>(metadata),
                    auditContext.ipAddress(), auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on data budget {}", action, budgetId, ex);
        }
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
