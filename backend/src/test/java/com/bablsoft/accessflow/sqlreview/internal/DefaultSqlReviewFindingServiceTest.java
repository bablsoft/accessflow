package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewResult;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.QuerySqlReviewFindingEntity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.repo.QuerySqlReviewFindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSqlReviewFindingServiceTest {

    @Mock QuerySqlReviewFindingRepository repository;

    private DefaultSqlReviewFindingService service;

    private final UUID queryId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    @BeforeEach
    void buildService() {
        service = new DefaultSqlReviewFindingService(repository,
                new SqlReviewFindingArgsCodec(new ObjectMapper()));
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    @Test
    void recordForQueryReplacesTheRowsAndEncodesEveryColumn() {
        var result = new SqlReviewResult(true, List.of(
                new SqlReviewFinding("protected_table", SqlReviewSeverity.BLOCK, 1, 7,
                        Map.of("table", "payroll.salaries")),
                new SqlReviewFinding("select_star", SqlReviewSeverity.WARN, 0, null, Map.of())));

        service.recordForQuery(queryId, result);

        var order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).deleteAllByQueryRequestId(queryId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QuerySqlReviewFindingEntity>> rows = ArgumentCaptor.forClass(List.class);
        order.verify(repository).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(2);
        var first = rows.getValue().get(0);
        assertThat(first.getId()).isNotNull();
        assertThat(first.getQueryRequestId()).isEqualTo(queryId);
        assertThat(first.getRequestGroupItemId()).isNull();
        assertThat(first.getRuleId()).isEqualTo("protected_table");
        assertThat(first.getSeverity()).isEqualTo(SqlReviewSeverity.BLOCK);
        assertThat(first.getStatementIndex()).isEqualTo(1);
        assertThat(first.getLineNumber()).isEqualTo(7);
        assertThat(first.getArgs()).isEqualTo("{\"table\":\"payroll.salaries\"}");
        var second = rows.getValue().get(1);
        assertThat(second.getLineNumber()).isNull();
        assertThat(second.getArgs()).isNull();
    }

    @Test
    void recordForGroupItemKeysTheRowsOffTheItem() {
        var result = new SqlReviewResult(true, List.of(
                new SqlReviewFinding("select_star", SqlReviewSeverity.BLOCK, 0, 1, Map.of())));

        service.recordForGroupItem(itemId, result);

        verify(repository).deleteAllByRequestGroupItemId(itemId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QuerySqlReviewFindingEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(rows.capture());
        assertThat(rows.getValue().get(0).getRequestGroupItemId()).isEqualTo(itemId);
        assertThat(rows.getValue().get(0).getQueryRequestId()).isNull();
    }

    @Test
    void aNotApplicableOrCleanResultStillClearsButWritesNothing() {
        service.recordForQuery(queryId, SqlReviewResult.notApplicable());
        service.recordForQuery(queryId, SqlReviewResult.clean());

        verify(repository, org.mockito.Mockito.times(2)).deleteAllByQueryRequestId(queryId);
        verify(repository, never()).saveAll(anyList());
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Test
    void findByQueryRequestDecodesRowsInRepositoryOrder() {
        when(repository.findAllByQueryRequestIdOrderByStatementIndexAscLineNumberAsc(queryId))
                .thenReturn(List.of(
                        row(queryId, null, "select_star", SqlReviewSeverity.WARN, 0, 1, null),
                        row(queryId, null, "protected_table", SqlReviewSeverity.BLOCK, 1, 3,
                                "{\"table\":\"t\"}")));

        var findings = service.findByQueryRequest(queryId);

        assertThat(findings).extracting(SqlReviewFinding::ruleId)
                .containsExactly("select_star", "protected_table");
        assertThat(findings.get(0).args()).isEmpty();
        assertThat(findings.get(1).args()).containsEntry("table", "t");
        assertThat(findings.get(1).lineNumber()).isEqualTo(3);
    }

    @Test
    void findByQueryRequestsGroupsByQueryAndSkipsTheLookupForAnEmptyInput() {
        var other = UUID.randomUUID();
        when(repository.findAllByQueryRequestIdInOrderByStatementIndexAscLineNumberAsc(
                List.of(queryId, other)))
                .thenReturn(List.of(
                        row(queryId, null, "a", SqlReviewSeverity.WARN, 0, 1, null),
                        row(other, null, "b", SqlReviewSeverity.BLOCK, 0, 1, null),
                        row(queryId, null, "c", SqlReviewSeverity.BLOCK, 1, 1, null)));

        var byQuery = service.findByQueryRequests(List.of(queryId, other));

        assertThat(byQuery.get(queryId)).extracting(SqlReviewFinding::ruleId).containsExactly("a", "c");
        assertThat(byQuery.get(other)).extracting(SqlReviewFinding::ruleId).containsExactly("b");
        assertThat(service.findByQueryRequests(List.of())).isEmpty();
        verify(repository, never())
                .findAllByQueryRequestIdInOrderByStatementIndexAscLineNumberAsc(List.of());
    }

    @Test
    void findByGroupItemsGroupsByItem() {
        when(repository.findAllByRequestGroupItemIdInOrderByStatementIndexAscLineNumberAsc(
                List.of(itemId)))
                .thenReturn(List.of(row(null, itemId, "a", SqlReviewSeverity.BLOCK, 0, 1, null)));

        var byItem = service.findByGroupItems(List.of(itemId));

        assertThat(byItem).containsOnlyKeys(itemId);
        assertThat(byItem.get(itemId).get(0).isBlocking()).isTrue();
        assertThat(service.findByGroupItems(List.of())).isEmpty();
    }

    @Test
    void blockingRuleIdsAreDistinctSortedAndExcludeWarn() {
        when(repository.findAllByQueryRequestIdOrderByStatementIndexAscLineNumberAsc(queryId))
                .thenReturn(List.of(
                        row(queryId, null, "select_star", SqlReviewSeverity.BLOCK, 1, 1, null),
                        row(queryId, null, "cross_join", SqlReviewSeverity.BLOCK, 0, 1, null),
                        row(queryId, null, "select_star", SqlReviewSeverity.BLOCK, 0, 2, null),
                        row(queryId, null, "missing_limit_on_select", SqlReviewSeverity.WARN, 0, 1,
                                null)));

        assertThat(service.blockingRuleIds(queryId)).containsExactly("cross_join", "select_star");
    }

    @Test
    void countBlockingMapsTheAggregateRowsAndSkipsTheLookupForAnEmptyInput() {
        var other = UUID.randomUUID();
        when(repository.countByQueryRequestIdsAndSeverity(List.of(queryId, other),
                SqlReviewSeverity.BLOCK))
                .thenReturn(List.<Object[]>of(new Object[]{queryId, 2L}));

        var counts = service.countBlockingByQueryRequests(List.of(queryId, other));

        assertThat(counts).containsExactly(Map.entry(queryId, 2));
        assertThat(service.countBlockingByQueryRequests(List.of())).isEmpty();
        verify(repository, never()).countByQueryRequestIdsAndSeverity(List.of(), SqlReviewSeverity.BLOCK);
    }

    private static QuerySqlReviewFindingEntity row(UUID queryId, UUID itemId, String ruleId,
                                                   SqlReviewSeverity severity, int statementIndex,
                                                   Integer line, String args) {
        var entity = new QuerySqlReviewFindingEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequestId(queryId);
        entity.setRequestGroupItemId(itemId);
        entity.setRuleId(ruleId);
        entity.setSeverity(severity);
        entity.setStatementIndex(statementIndex);
        entity.setLineNumber(line);
        entity.setArgs(args);
        return entity;
    }
}
