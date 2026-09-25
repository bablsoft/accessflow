package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.ByteSizeFormat;
import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.DataBudgetExhaustedException;
import com.bablsoft.accessflow.core.api.DataBudgetStatus;
import com.bablsoft.accessflow.core.api.DataBudgetStatusService;
import com.bablsoft.accessflow.core.api.DataBudgetUsageRecord;
import com.bablsoft.accessflow.core.api.DataBudgetUsageService;
import com.bablsoft.accessflow.core.api.DataBudgetUsageSource;
import com.bablsoft.accessflow.core.api.BytesScannedCapExceededException;
import com.bablsoft.accessflow.core.api.BytesScannedCapResolutionService;
import com.bablsoft.accessflow.core.api.QueryEstimateSnapshot;
import com.bablsoft.accessflow.proxy.api.QueryCostEstimateService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisPersistenceService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.MaskingPolicyResolutionService;
import com.bablsoft.accessflow.core.api.RowLimitPolicyResolutionService;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.lifecycle.api.LifecycleDirectiveResolutionService;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestNotFoundException;
import com.bablsoft.accessflow.core.api.QueryRequestPersistenceService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RecordExecutionCommand;
import com.bablsoft.accessflow.core.api.SqlCanonicalizer;
import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
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

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultQueryLifecycleService implements QueryLifecycleService {

    private final QueryRequestLookupService queryRequestLookupService;
    private final QueryRequestPersistenceService queryRequestPersistenceService;
    private final DatasourcePermissionVerifier permissionVerifier;
    private final com.bablsoft.accessflow.core.api.UserQueryService userQueryService;
    private final com.bablsoft.accessflow.core.api.RolePermissionResolver rolePermissionResolver;
    private final Clock clock;
    private final QueryRequestStateService queryRequestStateService;
    private final QueryResultPersistenceService queryResultPersistenceService;
    private final QueryExecutor queryExecutor;
    private final QueryParser queryParser;
    private final DatasourceLookupService datasourceLookupService;
    private final SqlCanonicalizer sqlCanonicalizer;
    private final DatasourceUserPermissionLookupService permissionLookupService;
    private final MaskingPolicyResolutionService maskingPolicyResolutionService;
    private final RowSecurityResolutionService rowSecurityResolutionService;
    private final RowLimitPolicyResolutionService rowLimitPolicyResolutionService;
    private final LifecycleDirectiveResolutionService lifecycleDirectiveResolutionService;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final AiAnalysisPersistenceService aiAnalysisPersistenceService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;
    private final ApplicationEventPublisher eventPublisher;
    private final BytesScannedCapResolutionService bytesScannedCapResolutionService;
    private final QueryCostEstimateService queryCostEstimateService;
    private final DataBudgetStatusService dataBudgetStatusService;
    private final DataBudgetUsageService dataBudgetUsageService;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @Override
    public void cancel(CancelQueryCommand command) {
        var query = loadOrThrow(command.queryRequestId(), command.callerOrganizationId());
        boolean isRecurringSeries = query.recurrenceRule() != null;
        if (!query.submittedByUserId().equals(command.callerUserId())
                // #627 kill-switch: a reviewer may cancel a recurring series (never a plain query).
                && !(command.callerIsReviewer() && isRecurringSeries)) {
            throw new AccessDeniedException(msg("error.query_not_owned_by_caller"));
        }
        var current = query.status();
        boolean isScheduledApproved = current == QueryStatus.APPROVED && query.scheduledFor() != null;
        boolean isRecurringApproved = current == QueryStatus.APPROVED && isRecurringSeries;
        if (current != QueryStatus.PENDING_AI
                && current != QueryStatus.PENDING_REVIEW
                && !isScheduledApproved
                && !isRecurringApproved) {
            throw new QueryNotCancellableException(query.id(), current);
        }
        queryRequestStateService.transitionTo(query.id(), current, QueryStatus.CANCELLED);
        if (isRecurringSeries && query.recurrenceNextRunAt() != null) {
            // Keep the "cursor cleared ⇒ series over" invariant and drop the row out of the
            // recurrence-due partial index (the job already filters on APPROVED regardless).
            queryRequestPersistenceService.clearRecurrenceNextRun(query.id(), null);
        }
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
        // A recurring parent (#627) must stay APPROVED for the series' lifetime — a manual
        // execute would consume the status and silently kill the series. Occurrence rows are
        // equally non-executable: a normal one is executed by the job in the tick that created
        // it, so an APPROVED occurrence reachable here is a crash orphan that already had its
        // slot consumed by the cursor advance.
        if (query.recurrenceRule() != null || query.recurringParentId() != null) {
            throw new QueryNotExecutableException(query.id(), query.status());
        }
        return doExecute(query, command.callerUserId(), null, true, AuditAction.QUERY_EXECUTED);
    }

    @Override
    public ExecutionOutcome executeBreakGlass(UUID queryRequestId, UUID actorUserId) {
        var query = queryRequestLookupService.findById(queryRequestId)
                .orElseThrow(() -> new QueryRequestNotFoundException(queryRequestId));
        if (query.status() != QueryStatus.APPROVED) {
            throw new QueryNotExecutableException(query.id(), query.status());
        }
        return doExecute(query, actorUserId, "break_glass", true,
                AuditAction.QUERY_BREAK_GLASS_EXECUTED);
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
        doExecute(query, query.submittedByUserId(), "scheduled", false, AuditAction.QUERY_EXECUTED);
    }

    @Override
    public void executeRecurringOccurrence(UUID parentQueryRequestId) {
        var parent = queryRequestLookupService.findById(parentQueryRequestId)
                .orElseThrow(() -> new QueryRequestNotFoundException(parentQueryRequestId));
        var now = clock.instant();
        if (parent.status() != QueryStatus.APPROVED || parent.recurrenceRule() == null
                || parent.recurrenceNextRunAt() == null
                || parent.recurrenceNextRunAt().isAfter(now)) {
            log.debug("Skipping recurring occurrence for {} — status={}, nextRunAt={}",
                    parent.id(), parent.status(), parent.recurrenceNextRunAt());
            return;
        }
        if (parent.recurrenceUntil() != null && now.isAfter(parent.recurrenceUntil())) {
            // Series completed: clear the cursor with no halt reason — the UI derives
            // "Series completed" from the cleared cursor + past expiry.
            queryRequestPersistenceService.clearRecurrenceNextRun(parent.id(), null);
            return;
        }
        // Fail-closed recheck with CURRENT state. Re-parse first: a parse failure would make the
        // referenced-table set empty and vacuously pass the allow-list (the tryGrantFastPath
        // idiom), so it halts the series instead. A corrupt stored rule halts the same way.
        // Only *deterministic* failures halt — a transient infrastructure error (pool blip,
        // timeout) propagates to the job's per-row catch and simply retries next tick, since
        // the cursor is still due.
        Instant next;
        try {
            var submitter = userQueryService.findById(parent.submittedByUserId())
                    .filter(com.bablsoft.accessflow.core.api.UserView::active)
                    .orElseThrow(() -> new AccessDeniedException(
                            "Submitter inactive or gone: " + parent.submittedByUserId()));
            var dbType = datasourceLookupService.findById(parent.datasourceId())
                    .filter(DatasourceConnectionDescriptor::active)
                    .map(DatasourceConnectionDescriptor::dbType)
                    .orElseThrow(() -> new AccessDeniedException(
                            "Datasource inactive or gone: " + parent.datasourceId()));
            var parsed = queryParser.parse(parent.sqlText(), dbType);
            // Admins bypass the per-datasource permission gate at submission; mirror that here so
            // an admin-submitted series isn't halted for lacking a permission row. Losing the
            // admin role (and holding no row) still halts — the fail-closed contract survives.
            var effectivePermissions = rolePermissionResolver.resolve(
                    submitter.roleId(), submitter.role());
            if (!effectivePermissions.contains(
                    com.bablsoft.accessflow.core.api.Permission.QUERY_ADMIN)) {
                permissionVerifier.verify(parent.submittedByUserId(), parent.datasourceId(),
                        parent.queryType(), parsed);
            }
            next = nextOccurrenceOrNull(parent, now);
        } catch (AccessDeniedException | InvalidSqlException | IllegalArgumentException
                 | java.time.format.DateTimeParseException ex) {
            haltSeries(parent, ex);
            return;
        }
        var childId = queryRequestPersistenceService
                .createRecurringOccurrence(parent.id(), parent.recurrenceNextRunAt(), next)
                .orElse(null);
        if (childId == null) {
            // A cancel/halt cleared the cursor, or a racing tick already fired this due window
            // and advanced it — the CAS under the parent lock is authoritative either way.
            log.debug("Recurring occurrence for {} skipped — cursor no longer matches",
                    parent.id());
            return;
        }
        var child = queryRequestLookupService.findById(childId)
                .orElseThrow(() -> new QueryRequestNotFoundException(childId));
        doExecute(child, parent.submittedByUserId(), "recurring", false,
                AuditAction.QUERY_EXECUTED);
    }

    /** Next cursor after {@code now}, or {@code null} when the series ends with this occurrence. */
    private Instant nextOccurrenceOrNull(QueryRequestSnapshot parent, Instant now) {
        var next = RecurrenceRule.parse(parent.recurrenceRule()).nextAfter(now);
        if (next == null
                || (parent.recurrenceUntil() != null && next.isAfter(parent.recurrenceUntil()))) {
            return null;
        }
        return next;
    }

    private void haltSeries(QueryRequestSnapshot parent, RuntimeException cause) {
        var reason = cause.getMessage() != null
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
        log.warn("Halting recurring series {} fail-closed: {}", parent.id(), reason);
        queryRequestPersistenceService.clearRecurrenceNextRun(parent.id(), reason);
        var metadata = new HashMap<String, Object>();
        metadata.put("reason", reason);
        metadata.put("recurrence_rule", parent.recurrenceRule());
        if (parent.recurrenceUntil() != null) {
            metadata.put("recurrence_until", parent.recurrenceUntil().toString());
        }
        metadata.put("datasource_id", parent.datasourceId().toString());
        recordAudit(AuditAction.RECURRING_SERIES_HALTED, parent.id(),
                parent.submittedByUserId(), parent.organizationId(), metadata);
    }

    private ExecutionOutcome doExecute(QueryRequestSnapshot query, UUID actorUserId,
                                       String trigger, boolean surfaceClientErrors,
                                       AuditAction successAction) {
        var startedAt = Instant.now();
        try {
            // #941: re-checked here, not only when the query left PENDING_AI — scheduled, recurring
            // and break-glass runs execute later or without that decision, and the cap may have
            // been lowered since. A refusal is recorded as a failed execution.
            var bytesCap = enforceBytesScannedCap(query);
            // #942: the reader's data budget. Allowance left ⇒ the result is capped to it; exhausted
            // ⇒ refused, unless the exhausted budget itself forced the review this query passed.
            // Break-glass is counted but never capped or refused by a budget.
            var budget = enforceDataBudget(query,
                    successAction == AuditAction.QUERY_BREAK_GLASS_EXECUTED);
            var permission = permissionLookupService
                    .findFor(query.submittedByUserId(), query.datasourceId());
            var restrictedColumns = permission
                    .map(p -> p.restrictedColumns())
                    .orElse(List.of());
            var descriptor = datasourceLookupService.findById(query.datasourceId());
            var dbType = descriptor
                    .map(DatasourceConnectionDescriptor::dbType)
                    .orElse(DbType.POSTGRESQL);
            var parsed = queryParser.parse(query.sqlText(), dbType);
            // #933: the merged per-user/per-group row cap; #934 lowers it further by every
            // row-limit policy on a referenced table. The executor clamps the result to the
            // datasource cap and the global ceiling, so it can only ever lower the limit.
            Integer grantRowLimit = permission
                    .map(p -> p.rowLimitOverride())
                    .orElse(null);
            Integer rowLimitOverride = grantRowLimit;
            Set<UUID> bindingRowLimitPolicyIds = Set.of();
            var appliedRowLimit = rowLimitPolicyResolutionService.resolve(query.organizationId(),
                    query.datasourceId(), query.submittedByUserId(), parsed.referencedTables());
            if (appliedRowLimit.isPresent()) {
                var policyCap = appliedRowLimit.get().maxRows();
                rowLimitOverride = appliedRowLimit.get().tighten(grantRowLimit);
                // Audit a policy only when it is what limits the result: at or below both the
                // grant override and the datasource cap.
                var datasourceCap = descriptor
                        .map(DatasourceConnectionDescriptor::maxRowsPerQuery)
                        .orElse(null);
                if ((grantRowLimit == null || policyCap <= grantRowLimit)
                        && (datasourceCap == null || policyCap <= datasourceCap)) {
                    bindingRowLimitPolicyIds = appliedRowLimit.get().policyIds();
                }
            }
            var maskingDirectives = maskingPolicyResolutionService
                    .resolveApplicable(query.organizationId(), query.datasourceId(),
                            query.submittedByUserId())
                    .stream()
                    .map(m -> new ColumnMaskDirective(m.columnRef(), m.strategy(), m.params(),
                            m.policyId()))
                    .toList();
            // Read-time pseudonymization (AF-499): enabled PSEUDONYMIZE retention policies contribute
            // additional column-mask directives, applied post-fetch by the same masker.
            var lifecycleMasks = lifecycleDirectiveResolutionService
                    .resolveColumnMasks(query.organizationId(), query.datasourceId());
            var columnMasks = Stream.concat(maskingDirectives.stream(), lifecycleMasks.stream())
                    .toList();
            var policyPredicates = rowSecurityResolutionService
                    .resolveApplicable(query.organizationId(), query.datasourceId(),
                            query.submittedByUserId())
                    .stream()
                    .map(p -> new RowSecurityDirective(p.policyId(), p.tableRef(), p.columnName(),
                            p.operator(), p.values()))
                    .toList();
            // Soft-delete (AF-499): read filters (marker IS NULL) join the row-security predicates;
            // the soft-delete directives drive the DELETE → UPDATE rewrite in the proxy.
            var softDeleteFilters = lifecycleDirectiveResolutionService
                    .resolveSoftDeleteFilters(query.organizationId(), query.datasourceId());
            var rowSecurityPredicates = Stream.concat(policyPredicates.stream(),
                    softDeleteFilters.stream()).toList();
            var softDeletes = lifecycleDirectiveResolutionService
                    .resolveSoftDeletes(query.organizationId(), query.datasourceId());
            var executionRequest = new QueryExecutionRequest(
                    query.datasourceId(), query.sqlText(), query.queryType(), rowLimitOverride,
                    null, restrictedColumns, columnMasks, rowSecurityPredicates, parsed.transactional(),
                    parsed.statements(), softDeletes, parsed.referencedTables());
            if (budget != null && budget.capped()) {
                executionRequest = executionRequest.withAllowance(
                        budget.status().remainingRows(), budget.status().remainingBytes());
            }
            var result = queryExecutor.execute(executionRequest);
            if (budget != null && budget.capped() && result instanceof SelectExecutionResult select) {
                result = attributeBudgetTruncation(select, budget.status().remainingRows(),
                        rowLimitOverride, descriptor.map(DatasourceConnectionDescriptor::maxRowsPerQuery)
                                .orElse(null));
            }
            var completedAt = Instant.now();
            var durationMs = (int) result.duration().toMillis();
            Long rowsAffected;
            Set<UUID> appliedMaskingPolicyIds = Set.of();
            Set<UUID> appliedRowSecurityPolicyIds;
            Set<UUID> appliedRowLimitPolicyIds = Set.of();
            String effectiveSql;
            switch (result) {
                case SelectExecutionResult select -> {
                    rowsAffected = select.rowCount();
                    appliedRowLimitPolicyIds = bindingRowLimitPolicyIds;
                    appliedMaskingPolicyIds = select.appliedMaskingPolicyIds();
                    appliedRowSecurityPolicyIds = select.appliedRowSecurityPolicyIds();
                    effectiveSql = select.effectiveSql();
                    persistSelectResult(query.id(), select, durationMs);
                }
                case UpdateExecutionResult update -> {
                    rowsAffected = update.rowsAffected();
                    appliedRowSecurityPolicyIds = update.appliedRowSecurityPolicyIds();
                    effectiveSql = update.effectiveSql();
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
            // AF-383 (UBA): enrich so behavioural baselines derive from audit_log alone (never query
            // result data). datasource_id groups a user's activity per datasource; query_type /
            // referenced_tables / rows_returned feed the tracked features.
            successMetadata.put("datasource_id", query.datasourceId().toString());
            successMetadata.put("query_type", query.queryType().name());
            successMetadata.put("referenced_tables",
                    parsed.referencedTables().stream().sorted().toList());
            successMetadata.put("distinct_table_count", parsed.referencedTables().size());
            successMetadata.put("rows_returned", rowsAffected);
            if (trigger != null) {
                successMetadata.put("trigger", trigger);
            }
            if (successAction == AuditAction.QUERY_BREAK_GLASS_EXECUTED) {
                successMetadata.put("break_glass", true);
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
            if (bytesCap != null) {
                successMetadata.put("bytes_scanned_cap", bytesCap.limit());
                successMetadata.put("bytes_scanned_cap_source", bytesCap.source().name());
                if (bytesCap.estimatedBytes() != null) {
                    successMetadata.put("bytes_scanned_estimate", bytesCap.estimatedBytes());
                }
            }
            if (!appliedRowLimitPolicyIds.isEmpty()) {
                successMetadata.put("applied_row_limit_policy_ids",
                        appliedRowLimitPolicyIds.stream()
                                .map(UUID::toString).sorted().toList());
            }
            if (budget != null && result instanceof SelectExecutionResult select) {
                successMetadata.put("data_budget_rows_charged", select.rowCount());
                successMetadata.put("data_budget_bytes_charged", select.resultBytes());
                chargeDataBudget(query, select);
            }
            recordAudit(successAction, query.id(), actorUserId,
                    query.organizationId(), successMetadata);
            eventPublisher.publishEvent(new QueryExecutedEvent(
                    query.id(), rowsAffected, durationMs, QueryStatus.EXECUTED,
                    query.recurringParentId(), effectiveSql));
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

    /**
     * @return the cap that applied and the estimate it was compared with, or {@code null} when no
     *         cap binds the submitter here
     * @throws BytesScannedCapExceededException when the estimate exceeds the cap, or is missing and
     *         the datasource rejects on a missing estimate. A missing estimate under
     *         {@code REQUIRE_REVIEW} passes: the review it demands happened before approval.
     */
    private BytesCapCheck enforceBytesScannedCap(QueryRequestSnapshot query) {
        var cap = bytesScannedCapResolutionService
                .resolve(query.datasourceId(), query.submittedByUserId())
                .orElse(null);
        if (cap == null) {
            return null;
        }
        Long estimated;
        try {
            estimated = queryCostEstimateService.estimateSubmittedQuery(query.id())
                    .filter(e -> !e.failed())
                    .map(QueryEstimateSnapshot::estimatedBytesScanned)
                    .orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Pre-flight estimate unavailable for query {} at execution: {}", query.id(),
                    ex.getMessage());
            estimated = null;
        }
        var check = BytesCapCheck.of(cap, estimated);
        if (!check.rejects()) {
            return check;
        }
        var limit = ByteSizeFormat.format(cap.limit());
        var message = estimated == null
                ? messageSource.getMessage("error.bytes_cap.no_estimate", new Object[]{limit},
                        LocaleContextHolder.getLocale())
                : messageSource.getMessage("error.bytes_cap.exceeded",
                        new Object[]{ByteSizeFormat.format(estimated), limit},
                        LocaleContextHolder.getLocale());
        var metadata = new HashMap<String, Object>();
        metadata.put("trigger", "bytes_scanned_cap");
        metadata.put("stage", "execution");
        metadata.put("limit", cap.limit());
        metadata.put("source", cap.source().name());
        if (estimated != null) {
            metadata.put("estimated_bytes", estimated);
        }
        metadata.put("outcome", check.outcome().name());
        recordAudit(AuditAction.QUERY_BYTES_SCANNED_CAP_ENFORCED, query.id(), null,
                query.organizationId(), metadata);
        throw new BytesScannedCapExceededException(message, cap, estimated, check.outcome());
    }

    /** A budget that applies to this read, and whether the result must be capped to it. */
    private record BudgetGate(DataBudgetStatus status, boolean capped) {
    }

    /**
     * @return {@code null} when no budget applies (or the statement is not a SELECT); otherwise the
     *         standing and whether to cap the result to the remaining allowance
     * @throws DataBudgetExhaustedException when an exhausted budget refuses this run
     */
    private BudgetGate enforceDataBudget(QueryRequestSnapshot query, boolean breakGlass) {
        if (query.queryType() != QueryType.SELECT) {
            return null;
        }
        var status = dataBudgetStatusService.statusFor(query.datasourceId(),
                query.submittedByUserId());
        if (status.isEmpty()) {
            return null;
        }
        if (breakGlass) {
            return new BudgetGate(status, false);
        }
        if (!status.exhausted()) {
            return new BudgetGate(status, true);
        }
        if (status.breachAction() == DataBudgetBreachAction.REQUIRE_REVIEW
                && budgetForcedReview(query)) {
            return new BudgetGate(status, false);
        }
        var deciding = status.decidingBudget();
        var metadata = new HashMap<String, Object>();
        metadata.put("trigger", "data_budget");
        metadata.put("stage", "execution");
        metadata.put("action", status.breachAction().name());
        metadata.put("data_budget_id", deciding.budgetId());
        metadata.put("used_rows", deciding.usedRows());
        metadata.put("used_bytes", deciding.usedBytes());
        metadata.put("window_minutes", deciding.windowMinutes());
        recordAudit(AuditAction.QUERY_DATA_BUDGET_ENFORCED, query.id(), null,
                query.organizationId(), metadata);
        throw new DataBudgetExhaustedException(messageSource.getMessage(
                "error.data_budget.exhausted", new Object[]{deciding.name()},
                LocaleContextHolder.getLocale()), deciding);
    }

    /**
     * The exhausted budget itself sent this query — or, for a recurring occurrence, its series — to
     * review, so its approval (by a reviewer or a synced ticket) was given knowing the budget was
     * spent. An approval obtained while allowance remained never lifts the budget.
     */
    private boolean budgetForcedReview(QueryRequestSnapshot query) {
        return queryRequestStateService.isDataBudgetReviewForced(query.id())
                || (query.recurringParentId() != null
                        && queryRequestStateService.isDataBudgetReviewForced(
                                query.recurringParentId()));
    }

    /**
     * The row allowance was the binding cap when the result stopped exactly at it and it sits below
     * every other row cap we know of; the global ceiling is covered by the exact-count test.
     */
    static SelectExecutionResult attributeBudgetTruncation(SelectExecutionResult select,
                                                           Long budgetRows, Integer otherRowCap,
                                                           Integer datasourceRowCap) {
        if (!select.truncated() || budgetRows == null
                || !SelectExecutionResult.TRUNCATED_ROW_LIMIT.equals(select.truncatedReason())
                || select.rowCount() != budgetRows
                || (otherRowCap != null && otherRowCap <= budgetRows)
                || (datasourceRowCap != null && datasourceRowCap <= budgetRows)) {
            return select;
        }
        return select.withTruncatedReason(SelectExecutionResult.TRUNCATED_DATA_BUDGET);
    }

    /** Never fails the execution: the rows were already delivered. */
    private void chargeDataBudget(QueryRequestSnapshot query, SelectExecutionResult select) {
        try {
            dataBudgetUsageService.record(new DataBudgetUsageRecord(query.submittedByUserId(),
                    query.datasourceId(), select.rowCount(), select.resultBytes(),
                    DataBudgetUsageSource.QUERY, query.id(), null));
        } catch (RuntimeException ex) {
            log.error("Data-budget usage write failed for query {}", query.id(), ex);
        }
    }

    private ExecutionOutcome recordFailure(QueryRequestSnapshot query, UUID actorUserId,
                                           String trigger, Instant startedAt, RuntimeException ex) {
        var completedAt = Instant.now();
        var durationMs = (int) java.time.Duration.between(startedAt, completedAt).toMillis();
        log.warn("Query execution failed for {}: {}", query.id(), ex.getMessage(), ex);
        // Prefer the verbatim driver message (the cause) over the generic localized summary so
        // the submitter/reviewer can see the actual database error on the detail page.
        var failureMessage = resolveFailureDetail(ex);
        queryRequestStateService.recordExecutionOutcome(new RecordExecutionCommand(
                query.id(), QueryStatus.FAILED, null, durationMs, failureMessage,
                startedAt, completedAt, null, null));
        var failureMetadata = new HashMap<String, Object>();
        failureMetadata.put("error", failureMessage);
        // AF-383 (UBA): datasource_id + query_type let the error-rate feature be computed per
        // (user, datasource) from audit_log alone.
        failureMetadata.put("datasource_id", query.datasourceId().toString());
        failureMetadata.put("query_type", query.queryType().name());
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
                query.id(), null, durationMs, QueryStatus.FAILED,
                query.recurringParentId()));
        return new ExecutionOutcome(query.id(), QueryStatus.FAILED, null, durationMs);
    }

    private static String resolveFailureDetail(RuntimeException ex) {
        if (ex instanceof QueryExecutionFailedException qef
                && qef.detail() != null && !qef.detail().isBlank()) {
            return qef.detail();
        }
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
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
                select.truncatedReason(),
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
