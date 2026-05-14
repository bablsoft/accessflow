package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RecordExecutionCommand;
import com.bablsoft.accessflow.proxy.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.proxy.api.QueryExecutionRequest;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.api.SelectExecutionResult;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.proxy.api.UpdateExecutionResult;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import com.bablsoft.accessflow.workflow.api.QueryNotCancellableException;
import com.bablsoft.accessflow.workflow.api.QueryNotExecutableException;
import com.bablsoft.accessflow.workflow.events.QueryExecutedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultQueryLifecycleService implements QueryLifecycleService {

    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryRequestStateService queryRequestStateService;
    private final QueryResultPersistenceService queryResultPersistenceService;
    private final QueryExecutor queryExecutor;
    private final SqlParserService sqlParserService;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;
    private final ApplicationEventPublisher eventPublisher;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @Override
    public void cancel(CancelQueryCommand command) {
        var query = loadOrThrow(command.queryRequestId(), command.callerOrganizationId());
        if (!query.submittedByUserId().equals(command.callerUserId())) {
            throw new AccessDeniedException(msg("error.query_not_owned_by_caller"));
        }
        var current = query.status();
        if (current != QueryStatus.PENDING_AI && current != QueryStatus.PENDING_REVIEW) {
            throw new QueryNotCancellableException(query.id(), current);
        }
        queryRequestStateService.transitionTo(query.id(), current, QueryStatus.CANCELLED);
        recordAudit(AuditAction.QUERY_CANCELLED, query.id(), command.callerUserId(),
                command.callerOrganizationId(), Map.of());
    }

    @Override
    public ExecutionOutcome execute(ExecuteQueryCommand command) {
        var query = loadOrThrow(command.queryRequestId(), command.callerOrganizationId());
        if (!command.isAdmin() && !query.submittedByUserId().equals(command.callerUserId())) {
            throw new AccessDeniedException(msg("error.query_not_owned_by_caller"));
        }
        if (query.status() != QueryStatus.APPROVED) {
            throw new QueryNotExecutableException(query.id(), query.status());
        }
        var startedAt = Instant.now();
        try {
            var restrictedColumns = permissionLookupService
                    .findFor(query.submittedByUserId(), query.datasourceId())
                    .map(p -> p.restrictedColumns())
                    .orElse(List.of());
            var parsed = sqlParserService.parse(query.sqlText());
            var result = queryExecutor.execute(new QueryExecutionRequest(
                    query.datasourceId(), query.sqlText(), query.queryType(), null, null,
                    restrictedColumns, parsed.transactional(), parsed.statements()));
            var completedAt = Instant.now();
            var durationMs = (int) result.duration().toMillis();
            Long rowsAffected;
            switch (result) {
                case SelectExecutionResult select -> {
                    rowsAffected = select.rowCount();
                    persistSelectResult(query.id(), select, durationMs);
                }
                case UpdateExecutionResult update -> rowsAffected = update.rowsAffected();
            }
            queryRequestStateService.recordExecutionOutcome(new RecordExecutionCommand(
                    query.id(), QueryStatus.EXECUTED, rowsAffected, durationMs, null,
                    startedAt, completedAt));
            recordAudit(AuditAction.QUERY_EXECUTED, query.id(), command.callerUserId(),
                    command.callerOrganizationId(), Map.of(
                            "rows_affected", rowsAffected,
                            "duration_ms", durationMs));
            eventPublisher.publishEvent(new QueryExecutedEvent(
                    query.id(), rowsAffected, durationMs, QueryStatus.EXECUTED));
            return new ExecutionOutcome(query.id(), QueryStatus.EXECUTED, rowsAffected, durationMs);
        } catch (RuntimeException ex) {
            var completedAt = Instant.now();
            var durationMs = (int) java.time.Duration.between(startedAt, completedAt).toMillis();
            log.warn("Query execution failed for {}: {}", query.id(), ex.getMessage(), ex);
            queryRequestStateService.recordExecutionOutcome(new RecordExecutionCommand(
                    query.id(), QueryStatus.FAILED, null, durationMs, ex.getMessage(),
                    startedAt, completedAt));
            var failureMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            var failureMetadata = new HashMap<String, Object>();
            failureMetadata.put("error", failureMessage);
            if (ex instanceof QueryExecutionFailedException qef && qef.sqlState() != null) {
                failureMetadata.put("sql_state", qef.sqlState());
                failureMetadata.put("vendor_code", qef.vendorCode());
            }
            recordAudit(AuditAction.QUERY_FAILED, query.id(), command.callerUserId(),
                    command.callerOrganizationId(), failureMetadata);
            eventPublisher.publishEvent(new QueryExecutedEvent(
                    query.id(), null, durationMs, QueryStatus.FAILED));
            return new ExecutionOutcome(query.id(), QueryStatus.FAILED, null, durationMs);
        }
    }

    private QueryRequestSnapshot loadOrThrow(UUID queryRequestId, UUID organizationId) {
        var query = queryRequestLookupService.findById(queryRequestId)
                .orElseThrow(() -> new QueryRequestNotFoundException(queryRequestId));
        if (!query.organizationId().equals(organizationId)) {
            throw new QueryRequestNotFoundException(queryRequestId);
        }
        return query;
    }

    private void persistSelectResult(UUID queryRequestId, SelectExecutionResult select,
                                     int durationMs) {
        var columnsArray = objectMapper.createArrayNode();
        for (var column : select.columns()) {
            ObjectNode node = columnsArray.addObject();
            node.put("name", column.name());
            node.put("type", column.typeName());
            node.put("restricted", column.restricted());
        }
        var rowsArray = objectMapper.valueToTree(select.rows());
        queryResultPersistenceService.save(new QueryResultPersistenceService.SaveResultCommand(
                queryRequestId,
                columnsArray.toString(),
                rowsArray.toString(),
                select.rowCount(),
                select.truncated(),
                durationMs));
    }

    private void recordAudit(AuditAction action, UUID queryRequestId, UUID callerUserId,
                             UUID organizationId, Map<String, Object> extraMetadata) {
        try {
            var metadata = new HashMap<String, Object>(extraMetadata);
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.QUERY_REQUEST,
                    queryRequestId,
                    organizationId,
                    callerUserId,
                    metadata,
                    null,
                    null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on query {}", action, queryRequestId, ex);
        }
    }
}
