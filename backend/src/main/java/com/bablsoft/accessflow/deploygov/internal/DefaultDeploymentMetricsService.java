package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsService;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView.ApprovalLatency;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView.ChangeFailureRate;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView.DailyBucket;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView.DeploymentFrequency;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView.LeadTime;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView.Period;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView.TimeToRestore;
import com.bablsoft.accessflow.deploygov.api.DeploymentMetricsView.TimeoutRate;
import com.bablsoft.accessflow.deploygov.api.InvalidDeploymentMetricsPeriodException;
import com.bablsoft.accessflow.deploygov.api.LeadTimeSource;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentMetricsRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentMetricsRepository.DailyStats;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentMetricsRepository.ExecutionStats;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentMetricsScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-time DORA and approval-SLA metrics (#984). The definitions live on
 * {@link DeploymentMetricsService}; this class validates the window, enforces per-pipeline
 * visibility, resolves the environment name, and shapes the repository's aggregates — including
 * the zero-filled UTC {@code daily} series.
 */
@Service
@RequiredArgsConstructor
public class DefaultDeploymentMetricsService implements DeploymentMetricsService {

    static final Duration DEFAULT_WINDOW = Duration.ofDays(30);
    static final Duration MAX_WINDOW = Duration.ofDays(366);
    private static final double SECONDS_PER_DAY = 86_400d;

    private final DeploymentMetricsRepository metricsRepository;
    private final DeploymentEnvironmentRepository environmentRepository;
    private final DeploymentPipelineVisibility visibility;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public DeploymentMetricsView pipelineMetrics(UUID pipelineId, UUID organizationId,
                                                 UUID callerId,
                                                 Set<Permission> callerPermissions,
                                                 DeploymentMetricsFilter filter) {
        var period = period(filter);
        visibility.requireVisible(pipelineId, organizationId, callerId, callerPermissions);
        List<UUID> environmentIds = null;
        if (hasEnvironment(filter)) {
            environmentIds = environmentRepository
                    .findByPipelineIdAndNameIgnoreCase(pipelineId, filter.environment().trim())
                    .map(DeploymentEnvironmentEntity::getId)
                    .stream().toList();
        }
        return compute(new DeploymentMetricsScope(organizationId, pipelineId, environmentIds,
                period.from(), period.to()));
    }

    @Override
    @Transactional(readOnly = true)
    public DeploymentMetricsView organizationMetrics(UUID organizationId,
                                                     DeploymentMetricsFilter filter) {
        var period = period(filter);
        List<UUID> environmentIds = null;
        if (hasEnvironment(filter)) {
            environmentIds = environmentRepository.findIdsByOrganizationIdAndNameIgnoreCase(
                    organizationId, filter.environment().trim());
        }
        var pipelineId = filter == null ? null : filter.pipelineId();
        return compute(new DeploymentMetricsScope(organizationId, pipelineId, environmentIds,
                period.from(), period.to()));
    }

    private Period period(DeploymentMetricsFilter filter) {
        var from = filter == null ? null : filter.from();
        var to = filter == null ? null : filter.to();
        try {
            if (to == null) {
                to = from == null ? clock.instant() : from.plus(DEFAULT_WINDOW);
            }
            if (from == null) {
                from = to.minus(DEFAULT_WINDOW);
            }
        } catch (DateTimeException ex) {
            // A bound within 30 days of Instant.MIN/MAX: unrepresentable default, not a 500.
            throw new InvalidDeploymentMetricsPeriodException();
        }
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new InvalidDeploymentMetricsPeriodException();
        }
        return new Period(from, to);
    }

    private static boolean hasEnvironment(DeploymentMetricsFilter filter) {
        return filter != null && filter.environment() != null && !filter.environment().isBlank();
    }

    private DeploymentMetricsView compute(DeploymentMetricsScope scope) {
        var period = new Period(scope.from(), scope.to());
        if (scope.environmentIds() != null && scope.environmentIds().isEmpty()) {
            // The name matched no environment: nothing can be in scope, so skip the queries.
            return empty(period);
        }
        var execution = metricsRepository.executionStats(scope);
        var restore = metricsRepository.restoreStats(scope);
        var approval = metricsRepository.approvalStats(scope);
        var review = metricsRepository.reviewStats(scope);
        var daily = metricsRepository.daily(scope);
        return new DeploymentMetricsView(
                period,
                frequency(execution.executed(), period),
                new LeadTime(seconds(execution.leadTimeP50()), seconds(execution.leadTimeP95()),
                        execution.executed(), leadTimeSource(execution)),
                new ChangeFailureRate(
                        rate(execution.failed() + execution.rolledBack(), execution.executed()),
                        execution.failed(), execution.rolledBack(), execution.unreported(),
                        execution.executed()),
                new TimeToRestore(seconds(restore.p50()), seconds(restore.p95()),
                        restore.restored(), restore.openIncidents()),
                new ApprovalLatency(seconds(approval.p50()), seconds(approval.p95()),
                        approval.sampleSize()),
                new TimeoutRate(rate(review.timedOut(), review.enteredReview()),
                        review.timedOut(), review.enteredReview()),
                review.breakGlass(),
                zeroFill(period, daily));
    }

    private static DeploymentMetricsView empty(Period period) {
        return new DeploymentMetricsView(period, frequency(0, period),
                new LeadTime(null, null, 0, null),
                new ChangeFailureRate(null, 0, 0, 0, 0),
                new TimeToRestore(null, null, 0, 0),
                new ApprovalLatency(null, null, 0),
                new TimeoutRate(null, 0, 0),
                0,
                zeroFill(period, List.of()));
    }

    private static DeploymentFrequency frequency(long total, Period period) {
        var days = Duration.between(period.from(), period.to()).toMillis() / 1000d / SECONDS_PER_DAY;
        return new DeploymentFrequency(total, total / days);
    }

    static LeadTimeSource leadTimeSource(ExecutionStats execution) {
        if (execution.executed() == 0) {
            return null;
        }
        if (execution.commitSourced() == execution.executed()) {
            return LeadTimeSource.COMMIT_TIMESTAMP;
        }
        return execution.commitSourced() == 0 ? LeadTimeSource.SUBMITTED_AT : LeadTimeSource.MIXED;
    }

    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static Long seconds(Double value) {
        return value == null ? null : Math.round(value);
    }

    /** One bucket per UTC day the window touches, {@code [from, to)}. */
    static List<DailyBucket> zeroFill(Period period, List<DailyStats> rows) {
        var byDate = rows.stream().collect(Collectors.toMap(
                row -> LocalDate.parse(row.date()), Function.identity()));
        var first = LocalDate.ofInstant(period.from(), ZoneOffset.UTC);
        var last = LocalDate.ofInstant(period.to().minusNanos(1), ZoneOffset.UTC);
        var buckets = new ArrayList<DailyBucket>();
        for (var day = first; !day.isAfter(last); day = day.plusDays(1)) {
            var row = byDate.get(day);
            buckets.add(row == null
                    ? new DailyBucket(day, 0, 0, 0)
                    : new DailyBucket(day, row.deployments(), row.failed(), row.rolledBack()));
        }
        return buckets;
    }
}
