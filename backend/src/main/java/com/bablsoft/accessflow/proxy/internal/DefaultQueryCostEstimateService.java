package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.PersistQueryEstimateCommand;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryEstimateLookupService;
import com.bablsoft.accessflow.core.api.QueryEstimatePersistenceService;
import com.bablsoft.accessflow.core.api.QueryEstimateSnapshot;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryPlanNode;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityResolutionService;
import com.bablsoft.accessflow.core.events.QueryEstimateCompletedEvent;
import com.bablsoft.accessflow.core.events.QueryEstimateFailedEvent;
import com.bablsoft.accessflow.proxy.api.QueryCostEstimateService;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reuses the AF-445 dry-run machinery ({@code QueryExecutor.dryRun} — dialect EXPLAIN planners and
 * engine {@code dryRun} overrides) plus the AF-624 affected-row count, bounded by the tighter
 * {@code accessflow.proxy.estimate-timeout}. Two independent triggers call this (the
 * submission-event listener and the AI analyzer, which needs the estimate for its prompt); the
 * persistence layer's insert-once semantics make the race harmless.
 */
@Service
@RequiredArgsConstructor
class DefaultQueryCostEstimateService implements QueryCostEstimateService {

    private static final Logger log = LoggerFactory.getLogger(DefaultQueryCostEstimateService.class);

    private final QueryEstimateLookupService queryEstimateLookupService;
    private final QueryEstimatePersistenceService queryEstimatePersistenceService;
    private final QueryRequestLookupService queryRequestLookupService;
    private final RowSecurityResolutionService rowSecurityResolutionService;
    private final QueryExecutor queryExecutor;
    private final ProxyPoolProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;
    private final Clock clock;

    @Override
    public Optional<QueryEstimateSnapshot> estimateSubmittedQuery(UUID queryRequestId) {
        var existing = queryEstimateLookupService.findByQueryRequestId(queryRequestId);
        if (existing.isPresent()) {
            return existing;
        }
        var snapshot = queryRequestLookupService.findById(queryRequestId).orElse(null);
        if (snapshot == null) {
            log.warn("Cost estimate skipped: query request {} not found", queryRequestId);
            return Optional.empty();
        }
        if (snapshot.transactional()) {
            return persistAndPublish(queryRequestId, unsupportedCommand(snapshot, null,
                    msg("error.estimate.transactional_unsupported")), true);
        }
        Instant start = clock.instant();
        try {
            var request = buildRequest(snapshot);
            var dryRun = queryExecutor.dryRun(request);
            Long affectedRows = countAffectedRows(request, snapshot.queryType());
            return persistAndPublish(queryRequestId,
                    toCommand(snapshot, dryRun, affectedRows, durationMs(start)), true);
        } catch (RuntimeException ex) {
            log.warn("Cost estimate failed for query {}: {}", queryRequestId, ex.getMessage());
            var command = new PersistQueryEstimateCommand(null, snapshot.queryType(), false, null,
                    null, null, null, null, null, null, true, truncate(ex.getMessage()),
                    durationMs(start));
            var result = persistAndPublish(queryRequestId, command, false);
            eventPublisher.publishEvent(
                    new QueryEstimateFailedEvent(queryRequestId, truncate(ex.getMessage())));
            return result;
        }
    }

    private QueryExecutionRequest buildRequest(QueryRequestSnapshot snapshot) {
        var rowSecurityPredicates = rowSecurityResolutionService
                .resolveApplicable(snapshot.organizationId(), snapshot.datasourceId(),
                        snapshot.submittedByUserId()).stream()
                .map(p -> new RowSecurityDirective(p.policyId(), p.tableRef(), p.columnName(),
                        p.operator(), p.values()))
                .toList();
        return new QueryExecutionRequest(snapshot.datasourceId(), snapshot.sqlText(),
                snapshot.queryType(), null, properties.estimateTimeout(), List.of(), List.of(),
                rowSecurityPredicates, false, null, List.of());
    }

    private Long countAffectedRows(QueryExecutionRequest request, QueryType queryType) {
        if (queryType != QueryType.UPDATE && queryType != QueryType.DELETE) {
            return null;
        }
        try {
            var count = queryExecutor.countAffectedRows(request);
            return count.supported() ? count.affectedRows() : null;
        } catch (RuntimeException ex) {
            log.debug("Affected-row count failed for datasource {}: {}",
                    request.datasourceId(), ex.getMessage());
            return null;
        }
    }

    private PersistQueryEstimateCommand toCommand(QueryRequestSnapshot snapshot,
                                                  QueryDryRunResult dryRun, Long affectedRows,
                                                  int durationMs) {
        if (!dryRun.supported()) {
            var reason = dryRun.unsupportedReason() != null
                    ? dryRun.unsupportedReason()
                    : msg("error.dry_run.unsupported", dryRun.engineId());
            var command = unsupportedCommand(snapshot, dryRun.engineId(), reason);
            // A degraded plan may still carry an exact write count (e.g. INSERT has no plan but
            // the COUNT rewrite worked) — keep it.
            return affectedRows == null ? command
                    : withAffectedRows(command, affectedRows, durationMs);
        }
        var root = dryRun.plan();
        var access = accessNode(root, snapshot.queryType());
        Long estimatedRows = dryRun.estimatedRows();
        if ((estimatedRows == null || estimatedRows == 0) && access != null
                && access != root && access.estimatedRows() != null) {
            estimatedRows = Math.round(access.estimatedRows());
        }
        return new PersistQueryEstimateCommand(
                dryRun.engineId(), snapshot.queryType(), true, estimatedRows,
                affectedRows,
                access != null ? truncateTo(access.operation(), 128) : null,
                root != null ? root.estimatedCost() : null,
                planJson(root),
                dryRun.rawPlan(), null, false, null, durationMs);
    }

    /**
     * Relational write plans wrap the access path in a modify node (e.g. PostgreSQL's
     * {@code ModifyTable} with {@code Plan Rows: 0}) — the scan type and row estimate a reviewer
     * or routing policy cares about live in its first child, so descend one level for writes.
     */
    private static QueryPlanNode accessNode(QueryPlanNode root, QueryType queryType) {
        if (root == null) {
            return null;
        }
        if ((queryType == QueryType.UPDATE || queryType == QueryType.DELETE
                || queryType == QueryType.INSERT) && !root.children().isEmpty()) {
            return root.children().get(0);
        }
        return root;
    }

    private PersistQueryEstimateCommand unsupportedCommand(QueryRequestSnapshot snapshot,
                                                           String engineId, String reason) {
        return new PersistQueryEstimateCommand(engineId, snapshot.queryType(), false, null, null,
                null, null, null, null, truncate(reason), false, null, null);
    }

    private static PersistQueryEstimateCommand withAffectedRows(PersistQueryEstimateCommand command,
                                                                Long affectedRows, int durationMs) {
        return new PersistQueryEstimateCommand(command.engineId(), command.queryType(),
                command.supported(), command.estimatedRows(), affectedRows, command.scanType(),
                command.estimatedCost(), command.planJson(), command.rawPlan(),
                command.unsupportedReason(), command.failed(), command.errorMessage(), durationMs);
    }

    /** Serializes the plan tree with explicit snake_case keys — the frontend's PlanTree shape. */
    private String planJson(QueryPlanNode root) {
        if (root == null) {
            return null;
        }
        return objectMapper.writeValueAsString(planNode(root));
    }

    private ObjectNode planNode(QueryPlanNode node) {
        var out = objectMapper.createObjectNode();
        out.put("operation", node.operation());
        out.put("target", node.target());
        if (node.estimatedRows() != null) {
            out.put("estimated_rows", node.estimatedRows());
        } else {
            out.putNull("estimated_rows");
        }
        if (node.estimatedCost() != null) {
            out.put("estimated_cost", node.estimatedCost());
        } else {
            out.putNull("estimated_cost");
        }
        out.put("detail", node.detail());
        var children = out.putArray("children");
        for (var child : node.children()) {
            children.add(planNode(child));
        }
        return out;
    }

    private Optional<QueryEstimateSnapshot> persistAndPublish(UUID queryRequestId,
                                                              PersistQueryEstimateCommand command,
                                                              boolean publishCompleted) {
        var estimateId = queryEstimatePersistenceService.persist(queryRequestId, command);
        if (publishCompleted) {
            eventPublisher.publishEvent(new QueryEstimateCompletedEvent(queryRequestId, estimateId,
                    command.supported()));
        }
        return queryEstimateLookupService.findByQueryRequestId(queryRequestId);
    }

    private int durationMs(Instant start) {
        return (int) Math.min(Duration.between(start, clock.instant()).toMillis(),
                Integer.MAX_VALUE);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String truncateTo(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args.length == 0 ? null : args,
                LocaleContextHolder.getLocale());
    }
}
