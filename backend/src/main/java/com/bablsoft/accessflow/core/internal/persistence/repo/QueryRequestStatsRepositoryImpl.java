package com.bablsoft.accessflow.core.internal.persistence.repo;

import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

class QueryRequestStatsRepositoryImpl implements QueryRequestStatsRepository {

    private static final String AGGREGATE_SQL = """
            SELECT q.datasource_id,
                   count(*),
                   count(*) FILTER (WHERE q.status = 'FAILED'::query_status),
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY q.execution_duration_ms)
                     FILTER (WHERE q.execution_duration_ms IS NOT NULL),
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY q.execution_duration_ms)
                     FILTER (WHERE q.execution_duration_ms IS NOT NULL)
            FROM query_requests q
            WHERE q.datasource_id IN (:datasourceIds)
              AND q.created_at > :since
            GROUP BY q.datasource_id
            """;

    private final EntityManager entityManager;

    QueryRequestStatsRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<DatasourceQueryStatsRow> aggregateByDatasource(Collection<UUID> datasourceIds,
                                                               Instant since) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(AGGREGATE_SQL)
                .setParameter("datasourceIds", datasourceIds)
                .setParameter("since", since)
                .getResultList();
        List<DatasourceQueryStatsRow> result = new ArrayList<>(rows.size());
        for (var columns : rows) {
            result.add(new DatasourceQueryStatsRow(
                    (UUID) columns[0],
                    ((Number) columns[1]).longValue(),
                    ((Number) columns[2]).longValue(),
                    asDouble(columns[3]),
                    asDouble(columns[4])));
        }
        return result;
    }

    private static Double asDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
