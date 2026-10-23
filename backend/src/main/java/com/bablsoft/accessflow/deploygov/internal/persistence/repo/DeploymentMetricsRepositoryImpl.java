package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Native SQL behind {@link DeploymentMetricsRepository} (#984). Enum values are explicit
 * {@code ::query_status} / {@code ::deployment_outcome} / {@code ::submission_reason} casts — a
 * JPQL enum literal fails against the PostgreSQL enum columns. Every value is a bound parameter;
 * the only text assembled at runtime is the choice between constant scope fragments.
 * Day buckets use {@code AT TIME ZONE 'UTC'}: a bare {@code date_trunc} on a
 * {@code timestamptz} buckets in the pgjdbc session zone (the JVM zone).
 */
@Repository
class DeploymentMetricsRepositoryImpl implements DeploymentMetricsRepository {

    private static final String PIPELINE_FRAGMENT = " AND r.pipeline_id = :pipelineId";
    private static final String ENVIRONMENT_FRAGMENT = " AND r.environment_id IN (:environmentIds)";

    // A malformed commit_timestamp falls back to created_at instead of failing the cast.
    private static final String EXECUTION_SQL = """
            SELECT count(*),
                   count(*) FILTER (WHERE x.outcome = 'FAILED'::deployment_outcome),
                   count(*) FILTER (WHERE x.outcome = 'ROLLED_BACK'::deployment_outcome),
                   count(*) FILTER (WHERE x.outcome IS NULL),
                   count(*) FILTER (WHERE x.commit_ts IS NOT NULL),
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM
                       (x.executed_at - COALESCE(x.commit_ts, x.created_at)))),
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM
                       (x.executed_at - COALESCE(x.commit_ts, x.created_at))))
            FROM (SELECT r.outcome, r.executed_at, r.created_at,
                         CASE WHEN pg_input_is_valid(r.metadata ->> 'commit_timestamp', 'timestamptz')
                              THEN CAST(r.metadata ->> 'commit_timestamp' AS timestamptz)
                         END AS commit_ts
                  FROM deployment_requests r
                  WHERE r.organization_id = :organizationId%s
                    AND r.executed_at >= :from AND r.executed_at < :to) x
            """;

    // The successor is any later SUCCEEDED execution on the same pipeline + environment — not
    // simply the next execution, which may itself have failed.
    private static final String RESTORE_SQL = """
            SELECT count(*) FILTER (WHERE x.restored_at IS NOT NULL),
                   count(*) FILTER (WHERE x.restored_at IS NULL),
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY GREATEST(0, extract(epoch FROM
                       (x.restored_at - x.outcome_reported_at)))) FILTER (WHERE x.restored_at IS NOT NULL),
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY GREATEST(0, extract(epoch FROM
                       (x.restored_at - x.outcome_reported_at)))) FILTER (WHERE x.restored_at IS NOT NULL)
            FROM (SELECT r.outcome_reported_at,
                         (SELECT min(s.executed_at)
                          FROM deployment_requests s
                          WHERE s.pipeline_id = r.pipeline_id
                            AND s.environment_id = r.environment_id
                            AND s.outcome = 'SUCCEEDED'::deployment_outcome
                            AND s.executed_at > r.executed_at) AS restored_at
                  FROM deployment_requests r
                  WHERE r.organization_id = :organizationId%s
                    AND r.outcome IN ('FAILED'::deployment_outcome, 'ROLLED_BACK'::deployment_outcome)
                    AND r.outcome_reported_at >= :from AND r.outcome_reported_at < :to) x
            """;

    private static final String APPROVAL_SQL = """
            SELECT count(*),
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM
                       (x.last_decided_at - x.review_started_at))),
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM
                       (x.last_decided_at - x.review_started_at)))
            FROM (SELECT r.review_started_at, max(d.decided_at) AS last_decided_at
                  FROM deployment_requests r
                  JOIN deployment_review_decisions d ON d.deployment_request_id = r.id
                  WHERE r.organization_id = :organizationId%s
                    AND r.review_started_at IS NOT NULL
                    AND r.submission_reason <> 'EMERGENCY_ACCESS'::submission_reason
                  GROUP BY r.id, r.review_started_at) x
            WHERE x.last_decided_at >= :from AND x.last_decided_at < :to
            """;

    private static final String REVIEW_SQL = """
            SELECT count(*) FILTER (WHERE x.entered AND x.status = 'TIMED_OUT'::query_status),
                   count(*) FILTER (WHERE x.entered),
                   count(*) FILTER (WHERE x.submission_reason = 'EMERGENCY_ACCESS'::submission_reason)
            FROM (SELECT r.status, r.submission_reason,
                         (r.review_started_at IS NOT NULL
                          OR r.status = 'TIMED_OUT'::query_status
                          OR EXISTS (SELECT 1 FROM deployment_review_decisions d
                                     WHERE d.deployment_request_id = r.id)) AS entered
                  FROM deployment_requests r
                  WHERE r.organization_id = :organizationId%s
                    AND r.created_at >= :from AND r.created_at < :to) x
            """;

    private static final String DAILY_SQL = """
            SELECT to_char(date_trunc('day', r.executed_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS day,
                   count(*),
                   count(*) FILTER (WHERE r.outcome = 'FAILED'::deployment_outcome),
                   count(*) FILTER (WHERE r.outcome = 'ROLLED_BACK'::deployment_outcome)
            FROM deployment_requests r
            WHERE r.organization_id = :organizationId%s
              AND r.executed_at >= :from AND r.executed_at < :to
            GROUP BY day
            ORDER BY day
            """;

    private final EntityManager entityManager;

    DeploymentMetricsRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public ExecutionStats executionStats(DeploymentMetricsScope scope) {
        var row = single(EXECUTION_SQL, scope);
        return new ExecutionStats(asLong(row[0]), asLong(row[1]), asLong(row[2]), asLong(row[3]),
                asLong(row[4]), asDouble(row[5]), asDouble(row[6]));
    }

    @Override
    public RestoreStats restoreStats(DeploymentMetricsScope scope) {
        var row = single(RESTORE_SQL, scope);
        return new RestoreStats(asLong(row[0]), asLong(row[1]), asDouble(row[2]),
                asDouble(row[3]));
    }

    @Override
    public ApprovalStats approvalStats(DeploymentMetricsScope scope) {
        var row = single(APPROVAL_SQL, scope);
        return new ApprovalStats(asLong(row[0]), asDouble(row[1]), asDouble(row[2]));
    }

    @Override
    public ReviewStats reviewStats(DeploymentMetricsScope scope) {
        var row = single(REVIEW_SQL, scope);
        return new ReviewStats(asLong(row[0]), asLong(row[1]), asLong(row[2]));
    }

    @Override
    public List<DailyStats> daily(DeploymentMetricsScope scope) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query(DAILY_SQL, scope).getResultList();
        var result = new ArrayList<DailyStats>(rows.size());
        for (var row : rows) {
            result.add(new DailyStats((String) row[0], asLong(row[1]), asLong(row[2]),
                    asLong(row[3])));
        }
        return result;
    }

    private Object[] single(String sql, DeploymentMetricsScope scope) {
        return (Object[]) query(sql, scope).getSingleResult();
    }

    private Query query(String sql, DeploymentMetricsScope scope) {
        var fragments = new StringBuilder();
        if (scope.pipelineId() != null) {
            fragments.append(PIPELINE_FRAGMENT);
        }
        if (scope.environmentIds() != null) {
            fragments.append(ENVIRONMENT_FRAGMENT);
        }
        var query = entityManager.createNativeQuery(sql.formatted(fragments))
                .setParameter("organizationId", scope.organizationId())
                .setParameter("from", scope.from())
                .setParameter("to", scope.to());
        if (scope.pipelineId() != null) {
            query.setParameter("pipelineId", scope.pipelineId());
        }
        if (scope.environmentIds() != null) {
            query.setParameter("environmentIds", scope.environmentIds());
        }
        return query;
    }

    private static long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static Double asDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
