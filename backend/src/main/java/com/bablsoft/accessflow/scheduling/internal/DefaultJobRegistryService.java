package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.scheduling.api.JobCadenceType;
import com.bablsoft.accessflow.scheduling.api.JobDescriptor;
import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import com.bablsoft.accessflow.scheduling.api.JobExecutionView;
import com.bablsoft.accessflow.scheduling.api.JobHealthSummary;
import com.bablsoft.accessflow.scheduling.api.JobMonitoringService;
import com.bablsoft.accessflow.scheduling.api.JobNotFoundException;
import com.bablsoft.accessflow.scheduling.api.JobRegistryView;
import com.bablsoft.accessflow.scheduling.internal.config.JobMonitoringProperties;
import com.bablsoft.accessflow.scheduling.internal.persistence.entity.JobExecutionEntity;
import com.bablsoft.accessflow.scheduling.internal.persistence.repo.JobExecutionRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.OneTimeTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Derives the job registry from the scheduler itself ({@link ScheduledTaskHolder}) instead of a
 * hand-maintained list, so it cannot drift from the jobs that are actually registered.
 *
 * <p>Spring wraps each task's runnable, so the {@code @Scheduled} method is identified through the
 * task's {@code toString()}, which delegates to {@code ScheduledMethodRunnable#toString()}
 * ({@code <declaring class>.<method>}). A task that does not resolve to a class (a programmatic
 * {@code SchedulingConfigurer} task) is skipped.
 *
 * <p>{@code schedulingEnabled} reports the {@code accessflow.scheduling.enabled} switch, not the
 * presence of a {@link ScheduledTaskHolder}: Spring Modulith's moments auto-configuration carries
 * its own {@code @EnableScheduling}, so the holder exists either way. The job list always reflects
 * what the holder actually scheduled.
 */
@Service
@Slf4j
class DefaultJobRegistryService implements JobMonitoringService {

    /** ShedLock's own fallback when a job leaves {@code lockAtMostFor} empty (see SchedulerLockConfiguration). */
    static final Duration DEFAULT_LOCK_AT_MOST_FOR = Duration.ofMinutes(10);

    static final String SCHEDULING_ENABLED_PROPERTY = "accessflow.scheduling.enabled";

    private final ObjectProvider<ScheduledTaskHolder> taskHolder;
    private final JobExecutionRepository repository;
    private final JobMonitoringProperties properties;
    private final Environment environment;
    private final Clock clock;

    DefaultJobRegistryService(ObjectProvider<ScheduledTaskHolder> taskHolder,
                              JobExecutionRepository repository,
                              JobMonitoringProperties properties,
                              Environment environment,
                              Clock clock) {
        this.taskHolder = taskHolder;
        this.repository = repository;
        this.properties = properties;
        this.environment = environment;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public JobRegistryView registry() {
        var schedulingEnabled = environment.getProperty(SCHEDULING_ENABLED_PROPERTY, Boolean.class, true);
        var holder = taskHolder.getIfAvailable();
        var registered = holder != null ? registeredJobs(holder) : Map.<String, RegisteredJob>of();
        if (!schedulingEnabled && registered.isEmpty()) {
            return new JobRegistryView(false, properties.isEnabled(), properties.summaryWindow(), List.of());
        }
        var now = clock.instant();
        var latest = new HashMap<String, JobExecutionEntity>();
        repository.findLatestPerJob().forEach(row -> latest.put(row.getJobName(), row));
        var consecutive = toCountMap(repository.countConsecutiveFailures());
        var window = windowStats(now.minus(properties.summaryWindow()));

        var names = new ArrayList<>(registered.keySet());
        latest.keySet().stream().filter(name -> !registered.containsKey(name)).sorted().forEach(names::add);

        var jobs = names.stream()
                .map(name -> {
                    var job = registered.get(name);
                    var lockAtMostFor = job != null ? job.lockAtMostFor() : DEFAULT_LOCK_AT_MOST_FOR;
                    var health = health(latest.get(name), consecutive.getOrDefault(name, 0L),
                            window.get(name), lockAtMostFor, now);
                    return job != null
                            ? new JobDescriptor(name, job.declaringClass(), job.methodName(), job.module(),
                                    job.cadenceType(), job.cadence(), job.lockName(), job.lockAtMostFor(),
                                    true, health)
                            : new JobDescriptor(name, null, null, null, null, null,
                                    latest.get(name).getLockName(), null, false, health);
                })
                .toList();
        return new JobRegistryView(schedulingEnabled, properties.isEnabled(), properties.summaryWindow(), jobs);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<JobExecutionView> executions(String jobName, JobExecutionStatus status,
                                                     Instant from, Instant to, PageRequest pageRequest) {
        var holder = taskHolder.getIfAvailable();
        var registered = holder != null ? registeredJobs(holder).get(jobName) : null;
        if (registered == null && !repository.existsByJobName(jobName)) {
            throw new JobNotFoundException(jobName);
        }
        var lockAtMostFor = registered != null ? registered.lockAtMostFor() : DEFAULT_LOCK_AT_MOST_FOR;
        var now = clock.instant();
        var pageable = org.springframework.data.domain.PageRequest.of(pageRequest.page(), pageRequest.size(),
                Sort.by(Sort.Direction.DESC, "startedAt"));
        var page = repository.findAll(filter(jobName, status, from, to), pageable);
        var content = page.getContent().stream().map(row -> toView(row, lockAtMostFor, now)).toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }

    static Specification<JobExecutionEntity> filter(String jobName, JobExecutionStatus status,
                                                    Instant from, Instant to) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("jobName"), jobName));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("startedAt"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    Map<String, RegisteredJob> registeredJobs(ScheduledTaskHolder holder) {
        var jobs = new LinkedHashMap<String, RegisteredJob>();
        holder.getScheduledTasks().stream()
                .map(this::describe)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(RegisteredJob::jobName))
                .forEach(job -> jobs.putIfAbsent(job.jobName(), job));
        return jobs;
    }

    Optional<RegisteredJob> describe(ScheduledTask scheduledTask) {
        var task = scheduledTask.getTask();
        var identity = task.toString();
        var dot = identity.lastIndexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        var className = identity.substring(0, dot);
        var methodName = identity.substring(dot + 1);
        Class<?> type;
        try {
            type = ClassUtils.forName(className, getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError ex) {
            log.debug("Scheduled task {} does not resolve to a class; not listed", identity);
            return Optional.empty();
        }
        var method = ReflectionUtils.findMethod(type, methodName);
        if (method == null) {
            log.debug("Scheduled task {} names no no-arg method; not listed", identity);
            return Optional.empty();
        }
        var lock = lockOf(method);
        return Optional.of(new RegisteredJob(JobNames.of(type, method), type.getName(), methodName,
                JobNames.moduleOf(type), cadenceType(task), cadence(task),
                lock != null ? environment.resolvePlaceholders(lock.name()) : null,
                lock != null ? parseDuration(lock.lockAtMostFor()) : null));
    }

    private static SchedulerLock lockOf(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock.class);
    }

    static JobCadenceType cadenceType(Task task) {
        return switch (task) {
            case FixedDelayTask ignored -> JobCadenceType.FIXED_DELAY;
            case FixedRateTask ignored -> JobCadenceType.FIXED_RATE;
            case CronTask ignored -> JobCadenceType.CRON;
            case OneTimeTask ignored -> JobCadenceType.ONE_TIME;
            default -> null;
        };
    }

    static String cadence(Task task) {
        return switch (task) {
            case CronTask cron -> cron.getExpression();
            case IntervalTask interval -> interval.getIntervalDuration().toString();
            default -> null;
        };
    }

    Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_LOCK_AT_MOST_FOR;
        }
        var resolved = environment.resolvePlaceholders(value).strip();
        try {
            return Duration.parse(resolved);
        } catch (DateTimeParseException ex) {
            try {
                return Duration.ofMillis(Long.parseLong(resolved));
            } catch (NumberFormatException nfe) {
                return DEFAULT_LOCK_AT_MOST_FOR;
            }
        }
    }

    private Map<String, WindowStats> windowStats(Instant since) {
        var stats = new HashMap<String, WindowStats>();
        for (var row : repository.summarizeSince(since,
                List.of(JobExecutionStatus.SUCCESS, JobExecutionStatus.FAILED))) {
            var name = (String) row[0];
            var status = (JobExecutionStatus) row[1];
            var count = ((Number) row[2]).longValue();
            var avg = row[3] != null ? ((Number) row[3]).doubleValue() : null;
            var current = stats.getOrDefault(name, WindowStats.EMPTY);
            stats.put(name, current.add(status, count, avg));
        }
        return stats;
    }

    private static Map<String, Long> toCountMap(List<Object[]> rows) {
        var counts = new HashMap<String, Long>();
        for (var row : rows) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private static JobHealthSummary health(JobExecutionEntity latest, long consecutiveFailures,
                                           WindowStats window, Duration lockAtMostFor, Instant now) {
        var stats = window != null ? window : WindowStats.EMPTY;
        if (latest == null) {
            return new JobHealthSummary(null, false, null, null, null, null, consecutiveFailures,
                    stats.successCount(), stats.failureCount(), stats.meanDurationMs());
        }
        return new JobHealthSummary(latest.getStatus(), isAbandoned(latest, lockAtMostFor, now),
                latest.getStartedAt(), latest.getFinishedAt(), latest.getDurationMs(),
                latest.getErrorMessage(), consecutiveFailures,
                stats.successCount(), stats.failureCount(), stats.meanDurationMs());
    }

    static JobExecutionView toView(JobExecutionEntity row, Duration lockAtMostFor, Instant now) {
        return new JobExecutionView(row.getId(), row.getJobName(), row.getLockName(), row.getInstanceId(),
                row.getStartedAt(), row.getFinishedAt(), row.getDurationMs(), row.getStatus(),
                row.getErrorClass(), row.getErrorMessage(), isAbandoned(row, lockAtMostFor, now));
    }

    static boolean isAbandoned(JobExecutionEntity row, Duration lockAtMostFor, Instant now) {
        return row.getStatus() == JobExecutionStatus.RUNNING
                && row.getStartedAt().plus(lockAtMostFor != null ? lockAtMostFor : DEFAULT_LOCK_AT_MOST_FOR)
                .isBefore(now);
    }

    record RegisteredJob(String jobName, String declaringClass, String methodName, String module,
                         JobCadenceType cadenceType, String cadence, String lockName,
                         Duration lockAtMostFor) {
    }

    record WindowStats(long successCount, long failureCount, double totalDurationMs, long durationSamples) {

        static final WindowStats EMPTY = new WindowStats(0, 0, 0, 0);

        WindowStats add(JobExecutionStatus status, long count, Double avg) {
            var success = successCount + (status == JobExecutionStatus.SUCCESS ? count : 0);
            var failure = failureCount + (status == JobExecutionStatus.FAILED ? count : 0);
            var total = totalDurationMs + (avg != null ? avg * count : 0);
            var samples = durationSamples + (avg != null ? count : 0);
            return new WindowStats(success, failure, total, samples);
        }

        Long meanDurationMs() {
            return durationSamples == 0 ? null : Math.round(totalDurationMs / durationSamples);
        }
    }
}
