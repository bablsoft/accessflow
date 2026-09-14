package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewResult;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.QuerySqlReviewFindingEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.QuerySqlReviewFindingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Writes findings at submission and serves them to the workflow and the reviewer surfaces (#864).
 * A record call replaces the owner's rows wholesale, so a re-evaluation never leaves stale findings
 * beside fresh ones. Separate from {@link DefaultSqlReviewService}, which is read-only by contract.
 */
@Service
@Transactional
class DefaultSqlReviewFindingService implements SqlReviewFindingService {

    private final QuerySqlReviewFindingRepository repository;
    private final SqlReviewFindingArgsCodec argsCodec;

    DefaultSqlReviewFindingService(QuerySqlReviewFindingRepository repository,
                                   SqlReviewFindingArgsCodec argsCodec) {
        this.repository = repository;
        this.argsCodec = argsCodec;
    }

    @Override
    public void recordForQuery(UUID queryRequestId, SqlReviewResult result) {
        repository.deleteAllByQueryRequestId(queryRequestId);
        persist(result, entity -> entity.setQueryRequestId(queryRequestId));
    }

    @Override
    public void recordForGroupItem(UUID requestGroupItemId, SqlReviewResult result) {
        repository.deleteAllByRequestGroupItemId(requestGroupItemId);
        persist(result, entity -> entity.setRequestGroupItemId(requestGroupItemId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SqlReviewFinding> findByQueryRequest(UUID queryRequestId) {
        return repository.findAllByQueryRequestIdOrderByStatementIndexAscLineNumberAsc(queryRequestId)
                .stream().map(this::toFinding).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<SqlReviewFinding>> findByQueryRequests(Collection<UUID> queryRequestIds) {
        if (queryRequestIds.isEmpty()) {
            return Map.of();
        }
        return group(repository.findAllByQueryRequestIdInOrderByStatementIndexAscLineNumberAsc(
                queryRequestIds), QuerySqlReviewFindingEntity::getQueryRequestId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<SqlReviewFinding>> findByGroupItems(Collection<UUID> requestGroupItemIds) {
        if (requestGroupItemIds.isEmpty()) {
            return Map.of();
        }
        return group(repository.findAllByRequestGroupItemIdInOrderByStatementIndexAscLineNumberAsc(
                requestGroupItemIds), QuerySqlReviewFindingEntity::getRequestGroupItemId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> blockingRuleIds(UUID queryRequestId) {
        return repository.findAllByQueryRequestIdOrderByStatementIndexAscLineNumberAsc(queryRequestId)
                .stream()
                .filter(entity -> entity.getSeverity() == SqlReviewSeverity.BLOCK)
                .map(QuerySqlReviewFindingEntity::getRuleId)
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> countBlockingByQueryRequests(Collection<UUID> queryRequestIds) {
        if (queryRequestIds.isEmpty()) {
            return Map.of();
        }
        var counts = new LinkedHashMap<UUID, Integer>();
        for (Object[] row : repository.countByQueryRequestIdsAndSeverity(queryRequestIds,
                SqlReviewSeverity.BLOCK)) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    private void persist(SqlReviewResult result, Consumer<QuerySqlReviewFindingEntity> owner) {
        if (!result.applicable() || result.findings().isEmpty()) {
            return;
        }
        var rows = new ArrayList<QuerySqlReviewFindingEntity>(result.findings().size());
        for (var finding : result.findings()) {
            var entity = new QuerySqlReviewFindingEntity();
            entity.setId(UUID.randomUUID());
            owner.accept(entity);
            entity.setRuleId(finding.ruleId());
            entity.setSeverity(finding.severity());
            entity.setStatementIndex(finding.statementIndex());
            entity.setLineNumber(finding.lineNumber());
            entity.setArgs(argsCodec.encode(finding.args()));
            rows.add(entity);
        }
        repository.saveAll(rows);
    }

    private Map<UUID, List<SqlReviewFinding>> group(List<QuerySqlReviewFindingEntity> rows,
                                                   Function<QuerySqlReviewFindingEntity, UUID> key) {
        var grouped = new LinkedHashMap<UUID, List<SqlReviewFinding>>();
        for (var row : rows) {
            grouped.computeIfAbsent(key.apply(row), ignored -> new ArrayList<>()).add(toFinding(row));
        }
        return grouped;
    }

    private SqlReviewFinding toFinding(QuerySqlReviewFindingEntity entity) {
        return new SqlReviewFinding(entity.getRuleId(), entity.getSeverity(),
                entity.getStatementIndex(), entity.getLineNumber(), argsCodec.decode(entity.getArgs()));
    }
}
