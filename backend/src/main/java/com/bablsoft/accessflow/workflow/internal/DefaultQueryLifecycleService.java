package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RecordExecutionCommand;
import com.bablsoft.accessflow.core.api.SqlCanonicalizer;
import com.bablsoft.accessflow.proxy.api.ColumnMaskDirective;
import com.bablsoft.accessflow.proxy.api.InvalidSqlException;
import com.bablsoft.accessflow.proxy.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.proxy.api.QueryExecutionRequest;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.api.RowSecurityDirective;
import com.bablsoft.accessflow.proxy.api.SelectExecutionResult;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.proxy.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.proxy.api.UpdateExecutionResult;
import com.bablsoft.accessflow.workflow.api.QueryLifecycleService;
import com.bablsoft.accessflow.workflow.api.QueryNotCancellableException;
import com.bablsoft.accessflow.workflow.api.QueryNotExecutableException;
import com.bablsoft.accessflow.workflow.api.QueryNotReanalyzableException;
import com.bablsoft.accessflow.core.events.AiReanalysisRequestedEvent;
import com.bablsoft.accessflow.workflow.events.QueryCancelledEvent;
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
import java.util.Set;
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
    private final SqlCanonicalizer sqlCanonicalizer;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final MaskingPolicyResolutionService maskingPolicyResolutionService;
    private final RowSecurityResolutionService rowSecurityResolutionService;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final AiAnalysisPersistenceService aiAnalysisPersistenceService;
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
        boolean isScheduledApproved = current == QueryStatus.APPROVED && query.scheduledFor() != null;
        if (current != QueryStatus.PENDING_AI
                && current != QueryStatus.PENDING_REVIEW
                && !isScheduledApproved) {
            throw new QueryNotCancellableException(query.id(), current);
        }
        queryRequestStateService.transitionTo(query.id(), current, QueryStatus.CANCELLED);
        eventPublisher.publishEvent(new QueryCancelledEvent(query.id(), command.callerUserId()));
    }

    @Override
    public void reanalyze(ReanalyzeQueryCommand command) {
        var query = loadOrThrow(command.queryRequestId(), command.callerOrganizationId());
        if (query.status() != QueryStatus.PENDING_REVIEW) {
            throw new QueryNotReanalyzableException(query.id(), query.status());
        }
        aiAnalysisLookupService.findByQueryRequestId(query.id())
                .filter(view -> view.failed())
                .orElseThrow(() -> new QueryNotReanalyzableException(query.id(), query.status()));
        aiAnalysisPersistenceService.deleteForQuery(query.id());
        eventPublisher.publishEvent(new AiReanalysisRequestedEvent(query.id(),
                command.callerUserId()));
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
        return doExecute(query, command.callerUserId(), null, true);
    }

    @Override
    public void executeScheduled(UUID queryRequestId) {
        var query = queryRequestLookupService.findById(queryRequestId)
                .orElseThrow(() -> new QueryRequestNotFoundException(queryRequestId));
        if (query.status() != QueryStatus.APPROVED || query.scheduledFor() == null
                || query.scheduledFor().isAfter(Instant.now())) {
            log.debug("Skipping scheduled execution for {} — status={}, scheduledFor={}",
                    query.id(), query.status(), query.scheduledFor());
            return;
        }
        doExecute(query, query.submittedByUserId(), "scheduled", false);
    }

    private ExecutionOutcome doExecute(QueryRequestSnapshot query, UUID actorUserId,
                                       String trigger, boolean surfaceClientErrors) {
        var startedAt = Instant.now();
        try {
            var restrictedColumns = permissionLookupService
                    .findFor(query.submittedByUserId(), query.datasourceId())
                    .map(p -> p.restrictedColumns())
                    .orElse(List.of());
            var columnMasks = maskingPolicyResolutionService
                    .resolveApplicable(query.organizationId(), query.datasourceId(),
                            query.submittedByUserId())
                    .stream()
                    .map(m -> new ColumnMaskDirective(m.columnRef(), m.strategy(), m.params(),
                            m.policyId()))
                    .toList();
            var rowSecurityPredicates = rowSecurityResolutionService
                    .resolveApplicable(query.organizationId(), query.datasourceId(),
                            query.submittedByUserId())
                    .stream()
                    .map(p -> new RowSecurityDirective(p.policyId(), p.tableRef(), p.columnName(),
                            p.operator(), p.values()))
                    .toList();
            var parsed = sqlParserService.parse(query.sqlText());
            var result = queryExecutor.execute(new QueryExecutionRequest(
                    query.datasourceId(), query.sqlText(), query.queryType(), null, null,
                    restrictedColumns, columnMasks, rowSecurityPredicates, parsed.transactional(),
                    parsed.statements()));
            var completedAt = Instant.now();
            var durationMs = (int) result.duration().toMillis();
            Long rowsAffected;
            Set<UUID> appliedMaskingPolicyIds = Set.of();
            Set<UUID> appliedRowSecurityPolicyIds;
            switch (result) {
                case SelectExecutionResult select -> {
                    rowsAffected = select.rowCount();
                    appliedMaskingPolicyIds = select.appliedMaskingPolicyIds();
                    appliedRowSecurityPolicyIds = select.appliedRowSecurityPolicyIds();
                    persistSelectResult(query.id(), select, durationMs);
                }
                case UpdateExecutionResult update -> {
                    rowsAffected = update.rowsAffected();
                    appliedRowSecurityPolicyIds = update.appliedRowSecurityPolicyIds();
                }
            }
            var canonicalSql = sqlCanonicalizer.canonicalize(query.sqlText());
            var previousRunId = queryRequestLookupService.findPreviousRunId(
                    query.submittedByUserId(), query.datasourceId(),
                    canonicalSql, query.id()).orElse(null);
            queryRequestStateService.recordExecutionOutcome(new RecordExecutionCommand(
                    query.id(), QueryStatus.EXECUTED, rowsAffected, durationMs, null,
                    startedAt, completedAt, canonicalSql, previousRunId));
            var successMetadata = new HashMap<String, Object>();
            successMetadata.put("rows_affected", rowsAffected);
            successMetadata.put("duration_ms", durationMs);
            if (trigger != null) {
                successMetadata.put("trigger", trigger);
            }
            if (!appliedMaskingPolicyIds.isEmpty()) {
                successMetadata.put("applied_masking_policy_ids", appliedMaskingPolicyIds.stream()
                        .map(UUID::toString).sorted().toList());
            }
            if (!appliedRowSecurityPolicyIds.isEmpty()) {
                successMetadata.put("applied_row_security_policy_ids",
                        appliedRowSecurityPolicyIds.stream()
                                .map(UUID::toString).sorted().toList());
            }
            recordAudit(AuditAction.QUERY_EXECUTED, query.id(), actorUserId,
                    query.organizationId(), successMetadata);
            eventPublisher.publishEvent(new QueryExecutedEvent(
                    query.id(), rowsAffected, durationMs, QueryStatus.EXECUTED));
            return new ExecutionOutcome(query.id(), QueryStatus.EXECUTED, rowsAffected, durationMs);
        } catch (UnrewritableRowSecurityException | InvalidSqlException ex) {
            // A structurally unfilterable (or unparseable) query is a client error. For an
            // interactive execute, surface it as 422 and leave the query untouched so the user can
            // act; for a system-driven scheduled run there is no caller to surface to, so record it
            // as a failed execution instead of looping forever.
            if (surfaceClientErrors) {
                throw ex;
            }
            return recordFailure(query, actorUserId, trigger, startedAt, ex);
        } catch (RuntimeException ex) {
            return recordFailure(query, actorUserId, trigger, startedAt, ex);
        }
    }

    private ExecutionOutcome recordFailure(QueryRequestSnapshot query, UUID actorUserId,
                                           String trigger, Instant startedAt, RuntimeException ex) {
        var completedAt = Instant.now();
        var durationMs = (int) java.time.Duration.between(startedAt, completedAt).toMillis();
        log.warn("Query execution failed for {}: {}", query.id(), ex.getMessage(), ex);
        queryRequestStateService.recordExecutionOutcome(new RecordExecutionCommand(
                query.id(), QueryStatus.FAILED, null, durationMs, ex.getMessage(),
                startedAt, completedAt, null, null));
        var failureMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        var failureMetadata = new HashMap<String, Object>();
        failureMetadata.put("error", failureMessage);
        if (ex instanceof QueryExecutionFailedException qef && qef.sqlState() != null) {
            failureMetadata.put("sql_state", qef.sqlState());
            failureMetadata.put("vendor_code", qef.vendorCode());
        }
        if (trigger != null) {
            failureMetadata.put("trigger", trigger);
        }
        recordAudit(AuditAction.QUERY_FAILED, query.id(), actorUserId,
                query.organizationId(), failureMetadata);
        eventPublisher.publishEvent(new QueryExecutedEvent(
                query.id(), null, durationMs, QueryStatus.FAILED));
        return new ExecutionOutcome(query.id(), QueryStatus.FAILED, null, durationMs);
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
