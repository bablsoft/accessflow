package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.QueryReplayService;
import com.bablsoft.accessflow.workflow.api.QueryReplayService.ReplayCommand;
import com.bablsoft.accessflow.workflow.api.QueryReplayService.ReplayResult;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotNotFoundException;
import com.bablsoft.accessflow.workflow.api.ReplaySchemaIncompatibleException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/queries")
@Tag(name = "Queries", description = "Query snapshot replay endpoints")
@RequiredArgsConstructor
@Slf4j
class QueryReplayController {

    private final QueryReplayService queryReplayService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;

    @PostMapping("/{id}/replay")
    @Operation(summary = "Replay an executed query's snapshot against a test datasource",
            description = "Re-submits the exact SQL of an executed query's immutable snapshot through the "
                    + "full review workflow against the target datasource. Validates engine + "
                    + "referenced-table compatibility; never bypasses approval.")
    @ApiResponse(responseCode = "202", description = "Replay accepted; a new query request enters the workflow")
    @ApiResponse(responseCode = "403", description = "Caller has no permission for the target datasource or query type")
    @ApiResponse(responseCode = "404", description = "No execution snapshot for the query, or target datasource not found")
    @ApiResponse(responseCode = "422", description = "Target datasource is a different engine or is missing referenced tables")
    ResponseEntity<SubmitQueryResponse> replay(
            @PathVariable UUID id,
            @Parameter(description = "Target (test) datasource to replay against", required = true)
            @RequestParam UUID targetDatasourceId,
            Authentication authentication,
            RequestAuditContext auditContext) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var result = queryReplayService.replay(new ReplayCommand(
                id,
                targetDatasourceId,
                caller.userId(),
                caller.organizationId(),
                caller.has(Permission.QUERY_ADMIN),
                auditContext.ipAddress(),
                auditContext.userAgent()));
        recordAudit(caller, id, result, auditContext);
        return ResponseEntity.accepted().body(new SubmitQueryResponse(
                result.newQueryId(), result.status(), null, null, null));
    }

    private void recordAudit(JwtClaims caller, UUID originalQueryId, ReplayResult result,
                             RequestAuditContext auditContext) {
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put("trigger", "replay");
            metadata.put("original_query_id", originalQueryId.toString());
            metadata.put("source_datasource_id", result.sourceDatasourceId().toString());
            metadata.put("target_datasource_id", result.targetDatasourceId().toString());
            if (result.sourceSchemaHash() != null) {
                metadata.put("source_schema_hash", result.sourceSchemaHash());
            }
            if (result.targetSchemaHash() != null) {
                metadata.put("target_schema_hash", result.targetSchemaHash());
            }
            auditLogService.record(new AuditEntry(
                    AuditAction.QUERY_SUBMITTED,
                    AuditResourceType.QUERY_REQUEST,
                    result.newQueryId(),
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for replay of query {} (new query {})",
                    originalQueryId, result.newQueryId(), ex);
        }
    }

    @ExceptionHandler(QuerySnapshotNotFoundException.class)
    ResponseEntity<ProblemDetail> handleSnapshotNotFound(QuerySnapshotNotFoundException ex) {
        var detail = msg("error.query_snapshot_not_found",
                new Object[]{ex.queryRequestId().toString()});
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        pd.setProperty("error", "QUERY_SNAPSHOT_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    @ExceptionHandler(ReplaySchemaIncompatibleException.class)
    ResponseEntity<ProblemDetail> handleSchemaIncompatible(ReplaySchemaIncompatibleException ex) {
        var detail = switch (ex.reason()) {
            case DB_TYPE_MISMATCH -> msg("error.replay_schema_incompatible_db_type",
                    new Object[]{String.valueOf(ex.expectedDbType()), String.valueOf(ex.actualDbType())});
            case MISSING_TABLES -> msg("error.replay_schema_incompatible_missing_tables",
                    new Object[]{String.join(", ", ex.missingTables())});
            case TARGET_SCHEMA_UNAVAILABLE -> msg("error.replay_target_schema_unavailable", null);
        };
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, detail);
        pd.setProperty("error", "REPLAY_SCHEMA_INCOMPATIBLE");
        if (!ex.missingTables().isEmpty()) {
            pd.setProperty("missing_tables", ex.missingTables());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(pd);
    }

    private String msg(String key, Object[] args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
