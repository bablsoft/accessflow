package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.scheduling.api.JobCadenceType;
import com.bablsoft.accessflow.scheduling.api.JobDescriptor;
import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import com.bablsoft.accessflow.scheduling.api.JobNotFoundException;
import com.bablsoft.accessflow.scheduling.internal.config.JobMonitoringProperties;
import com.bablsoft.accessflow.scheduling.internal.persistence.entity.JobExecutionEntity;
import com.bablsoft.accessflow.scheduling.internal.persistence.repo.JobExecutionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.OneTimeTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultJobRegistryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    @Mock ObjectProvider<ScheduledTaskHolder> holderProvider;
    @Mock ScheduledTaskHolder holder;
    @Mock JobExecutionRepository repository;

    private DefaultJobRegistryService service;

    @BeforeEach
    void setUp() {
        var environment = new MockEnvironment().withProperty("test.lock-at-most", "PT3M");
        service = new DefaultJobRegistryService(holderProvider, repository,
                new JobMonitoringProperties(true, null, null, null, Duration.ofHours(24)),
                environment, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(repository.findLatestPerJob()).thenReturn(List.of());
        lenient().when(repository.countConsecutiveFailures()).thenReturn(List.of());
        lenient().when(repository.summarizeSince(any(), anyList())).thenReturn(List.of());
    }

    private DefaultJobRegistryService withSchedulingSwitch(boolean enabled) {
        var environment = new MockEnvironment().withProperty("accessflow.scheduling.enabled", String.valueOf(enabled));
        return new DefaultJobRegistryService(holderProvider, repository,
                new JobMonitoringProperties(true, null, null, null, Duration.ofHours(24)),
                environment, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void disabledSchedulerYieldsAnEmptyRegistryNotAnError() {
        when(holderProvider.getIfAvailable()).thenReturn(holder);
        when(holder.getScheduledTasks()).thenReturn(Set.of());

        var view = withSchedulingSwitch(false).registry();

        assertThat(view.schedulingEnabled()).isFalse();
        assertThat(view.recordingEnabled()).isTrue();
        assertThat(view.summaryWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(view.jobs()).isEmpty();
    }

    @Test
    void absentHolderWithTheSwitchOnListsNoJobs() {
        when(holderProvider.getIfAvailable()).thenReturn(null);

        var view = service.registry();

        assertThat(view.schedulingEnabled()).isTrue();
        assertThat(view.jobs()).isEmpty();
    }

    @Test
    void jobsTheHolderActuallyScheduledAreListedEvenWithTheSwitchOff() throws Exception {
        var scheduledTasks = tasks(
                new FixedDelayTask(runnable(SampleLockedJob.class), Duration.ofMinutes(5), Duration.ZERO));
        when(holderProvider.getIfAvailable()).thenReturn(holder);
        when(holder.getScheduledTasks()).thenReturn(scheduledTasks);

        var view = withSchedulingSwitch(false).registry();

        assertThat(view.schedulingEnabled()).isFalse();
        assertThat(view.jobs()).extracting(JobDescriptor::jobName).containsExactly("SampleLockedJob");
    }

    @Test
    void describesEachRegisteredTaskFromTheHolder() throws Exception {
        when(holderProvider.getIfAvailable()).thenReturn(holder);
        var scheduledTasks = tasks(
                new FixedDelayTask(runnable(SampleLockedJob.class), Duration.ofMinutes(5), Duration.ZERO),
                new CronTask(runnable(SampleCronJob.class), "0 0 * * * *"));
        when(holder.getScheduledTasks()).thenReturn(scheduledTasks);

        var jobs = service.registry().jobs();

        assertThat(jobs).extracting(JobDescriptor::jobName).containsExactly("SampleCronJob", "SampleLockedJob");
        var locked = jobs.get(1);
        assertThat(locked.declaringClass()).isEqualTo(SampleLockedJob.class.getName());
        assertThat(locked.methodName()).isEqualTo("run");
        assertThat(locked.module()).isEqualTo("scheduling");
        assertThat(locked.cadenceType()).isEqualTo(JobCadenceType.FIXED_DELAY);
        assertThat(locked.cadence()).isEqualTo("PT5M");
        assertThat(locked.lockName()).isEqualTo("sampleLockedJob");
        assertThat(locked.lockAtMostFor()).isEqualTo(Duration.ofMinutes(3));
        assertThat(locked.registered()).isTrue();
        var cron = jobs.get(0);
        assertThat(cron.cadenceType()).isEqualTo(JobCadenceType.CRON);
        assertThat(cron.cadence()).isEqualTo("0 0 * * * *");
        assertThat(cron.lockName()).isNull();
        assertThat(cron.lockAtMostFor()).isNull();
    }

    @Test
    void listsEveryScheduledMethodInTheApplication() {
        var tasks = new ArrayList<Task>();
        var expectedLocks = new ArrayList<String>();
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        for (var candidate : scanner.findCandidateComponents("com.bablsoft.accessflow")) {
            var type = load(candidate.getBeanClassName());
            for (var method : type.getMethods()) {
                if (method.isAnnotationPresent(Scheduled.class)) {
                    tasks.add(new FixedDelayTask(new ScheduledMethodRunnable(new Object(), method),
                            Duration.ofMinutes(1), Duration.ZERO));
                    expectedLocks.add(method.getAnnotation(SchedulerLock.class).name());
                }
            }
        }
        when(holderProvider.getIfAvailable()).thenReturn(holder);
        var scheduledTasks = tasks(tasks.toArray(Task[]::new));
        when(holder.getScheduledTasks()).thenReturn(scheduledTasks);

        var jobs = service.registry().jobs();

        assertThat(tasks).hasSizeGreaterThanOrEqualTo(29);
        assertThat(jobs).hasSize(tasks.size());
        assertThat(jobs).extracting(JobDescriptor::lockName).containsExactlyInAnyOrderElementsOf(expectedLocks);
        assertThat(jobs).allSatisfy(job -> assertThat(job.module()).isNotNull());
        assertThat(jobs).extracting(JobDescriptor::jobName).contains("JobExecutionRetentionJob", "QueryTimeoutJob");
    }

    @Test
    void mergesTheHealthRollupAndAppendsHistoryOnlyJobs() throws Exception {
        when(holderProvider.getIfAvailable()).thenReturn(holder);
        var scheduledTasks = tasks(
                new FixedDelayTask(runnable(SampleLockedJob.class), Duration.ofMinutes(5), Duration.ZERO));
        when(holder.getScheduledTasks()).thenReturn(scheduledTasks);
        var latest = row("SampleLockedJob", JobExecutionStatus.FAILED, NOW.minusSeconds(60));
        latest.setErrorMessage("boom");
        latest.setDurationMs(40L);
        var ghost = row("RemovedJob", JobExecutionStatus.SUCCESS, NOW.minusSeconds(3600));
        ghost.setLockName("removedJob");
        when(repository.findLatestPerJob()).thenReturn(List.of(latest, ghost));
        when(repository.countConsecutiveFailures()).thenReturn(List.<Object[]>of(new Object[] {"SampleLockedJob", 3L}));
        when(repository.summarizeSince(NOW.minus(Duration.ofHours(24)),
                List.of(JobExecutionStatus.SUCCESS, JobExecutionStatus.FAILED))).thenReturn(List.of(
                new Object[] {"SampleLockedJob", JobExecutionStatus.SUCCESS, 3L, 10.0},
                new Object[] {"SampleLockedJob", JobExecutionStatus.FAILED, 1L, 50.0},
                new Object[] {"RemovedJob", JobExecutionStatus.FAILED, 2L, null}));

        var jobs = service.registry().jobs();

        assertThat(jobs).extracting(JobDescriptor::jobName).containsExactly("SampleLockedJob", "RemovedJob");
        var health = jobs.get(0).health();
        assertThat(health.lastStatus()).isEqualTo(JobExecutionStatus.FAILED);
        assertThat(health.lastErrorMessage()).isEqualTo("boom");
        assertThat(health.lastDurationMs()).isEqualTo(40L);
        assertThat(health.consecutiveFailures()).isEqualTo(3);
        assertThat(health.windowSuccessCount()).isEqualTo(3);
        assertThat(health.windowFailureCount()).isEqualTo(1);
        assertThat(health.windowMeanDurationMs()).isEqualTo(20L);
        var removed = jobs.get(1);
        assertThat(removed.registered()).isFalse();
        assertThat(removed.lockName()).isEqualTo("removedJob");
        assertThat(removed.health().windowFailureCount()).isEqualTo(2);
        assertThat(removed.health().windowMeanDurationMs()).isNull();
    }

    @Test
    void jobWithoutHistoryHasAnEmptyRollup() throws Exception {
        when(holderProvider.getIfAvailable()).thenReturn(holder);
        var scheduledTasks = tasks(
                new FixedDelayTask(runnable(SampleLockedJob.class), Duration.ofMinutes(5), Duration.ZERO));
        when(holder.getScheduledTasks()).thenReturn(scheduledTasks);

        var health = service.registry().jobs().getFirst().health();

        assertThat(health.lastStatus()).isNull();
        assertThat(health.lastAbandoned()).isFalse();
        assertThat(health.consecutiveFailures()).isZero();
    }

    @Test
    void runningRowOlderThanLockAtMostForIsAbandoned() throws Exception {
        when(holderProvider.getIfAvailable()).thenReturn(holder);
        var scheduledTasks = tasks(
                new FixedDelayTask(runnable(SampleLockedJob.class), Duration.ofMinutes(5), Duration.ZERO));
        when(holder.getScheduledTasks()).thenReturn(scheduledTasks);
        when(repository.findLatestPerJob()).thenReturn(List.of(
                row("SampleLockedJob", JobExecutionStatus.RUNNING, NOW.minus(Duration.ofMinutes(4)))));

        assertThat(service.registry().jobs().getFirst().health().lastAbandoned()).isTrue();
    }

    @Test
    void abandonedDetection() {
        var running = row("J", JobExecutionStatus.RUNNING, NOW.minus(Duration.ofMinutes(2)));
        assertThat(DefaultJobRegistryService.isAbandoned(running, Duration.ofMinutes(1), NOW)).isTrue();
        assertThat(DefaultJobRegistryService.isAbandoned(running, Duration.ofMinutes(5), NOW)).isFalse();
        assertThat(DefaultJobRegistryService.isAbandoned(running, null, NOW)).isFalse();
        var done = row("J", JobExecutionStatus.SUCCESS, NOW.minus(Duration.ofDays(1)));
        assertThat(DefaultJobRegistryService.isAbandoned(done, Duration.ofMinutes(1), NOW)).isFalse();
    }

    @Test
    void executionsOfAnUnknownJobIs404() {
        when(holderProvider.getIfAvailable()).thenReturn(null);
        when(repository.existsByJobName("Nope")).thenReturn(false);

        assertThatThrownBy(() -> service.executions("Nope", null, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(JobNotFoundException.class)
                .hasMessageContaining("Nope");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executionsOfAHistoryOnlyJobArePagedNewestFirst() {
        when(holderProvider.getIfAvailable()).thenReturn(null);
        when(repository.existsByJobName("RemovedJob")).thenReturn(true);
        var running = row("RemovedJob", JobExecutionStatus.RUNNING, NOW.minus(Duration.ofHours(1)));
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable pageable = inv.getArgument(1);
                    assertThat(pageable.getSort().getOrderFor("startedAt").isDescending()).isTrue();
                    return new PageImpl<>(List.of(running), pageable, 1);
                });

        var page = service.executions("RemovedJob", JobExecutionStatus.RUNNING, NOW.minusSeconds(7200), NOW,
                PageRequest.of(0, 20));

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().getFirst().abandoned()).isTrue();
        assertThat(page.content().getFirst().status()).isEqualTo(JobExecutionStatus.RUNNING);
    }

    @Test
    @SuppressWarnings("unchecked")
    void executionsOfARegisteredJobUseItsLockCeiling() throws Exception {
        when(holderProvider.getIfAvailable()).thenReturn(holder);
        var scheduledTasks = tasks(
                new FixedDelayTask(runnable(SampleLockedJob.class), Duration.ofMinutes(5), Duration.ZERO));
        when(holder.getScheduledTasks()).thenReturn(scheduledTasks);
        var running = row("SampleLockedJob", JobExecutionStatus.RUNNING, NOW.minus(Duration.ofMinutes(2)));
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(running), (Pageable) inv.getArgument(1), 1));

        var page = service.executions("SampleLockedJob", null, null, null, PageRequest.of(0, 20));

        assertThat(page.content().getFirst().abandoned()).isFalse();
    }

    @Test
    void cadenceOfEveryTaskKind() {
        Runnable noop = () -> { };
        assertThat(DefaultJobRegistryService.cadenceType(new FixedRateTask(noop, Duration.ofSeconds(30), Duration.ZERO)))
                .isEqualTo(JobCadenceType.FIXED_RATE);
        assertThat(DefaultJobRegistryService.cadence(new FixedRateTask(noop, Duration.ofSeconds(30), Duration.ZERO)))
                .isEqualTo("PT30S");
        assertThat(DefaultJobRegistryService.cadenceType(new OneTimeTask(noop, Duration.ofSeconds(1))))
                .isEqualTo(JobCadenceType.ONE_TIME);
        var trigger = new TriggerTask(noop, ctx -> null);
        assertThat(DefaultJobRegistryService.cadenceType(trigger)).isNull();
        assertThat(DefaultJobRegistryService.cadence(trigger)).isNull();
    }

    @Test
    void lockDurationParsing() {
        assertThat(service.parseDuration("PT2M")).isEqualTo(Duration.ofMinutes(2));
        assertThat(service.parseDuration("90000")).isEqualTo(Duration.ofSeconds(90));
        assertThat(service.parseDuration("${test.lock-at-most}")).isEqualTo(Duration.ofMinutes(3));
        assertThat(service.parseDuration("")).isEqualTo(DefaultJobRegistryService.DEFAULT_LOCK_AT_MOST_FOR);
        assertThat(service.parseDuration(null)).isEqualTo(DefaultJobRegistryService.DEFAULT_LOCK_AT_MOST_FOR);
        assertThat(service.parseDuration("soon")).isEqualTo(DefaultJobRegistryService.DEFAULT_LOCK_AT_MOST_FOR);
    }

    @Test
    void taskThatDoesNotResolveToAClassIsSkipped() {
        var task = new FixedDelayTask(new Runnable() {
            @Override public void run() { }
            @Override public String toString() { return "com.example.Missing.run"; }
        }, Duration.ofMinutes(1), Duration.ZERO);
        var unnamed = new FixedDelayTask(new Runnable() {
            @Override public void run() { }
            @Override public String toString() { return "lambda"; }
        }, Duration.ofMinutes(1), Duration.ZERO);

        var missingMethod = new FixedDelayTask(new Runnable() {
            @Override public void run() { }
            @Override public String toString() { return SampleCronJob.class.getName() + ".gone"; }
        }, Duration.ofMinutes(1), Duration.ZERO);

        assertThat(service.describe(scheduled(task))).isEmpty();
        assertThat(service.describe(scheduled(unnamed))).isEmpty();
        assertThat(service.describe(scheduled(missingMethod))).isEmpty();
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ScheduledMethodRunnable runnable(Class<?> type) throws NoSuchMethodException {
        Method method = type.getMethod("run");
        return new ScheduledMethodRunnable(mock(type), method);
    }

    private static Set<ScheduledTask> tasks(Task... tasks) {
        var result = new LinkedHashSet<ScheduledTask>();
        Arrays.stream(tasks).map(DefaultJobRegistryServiceTest::scheduled).forEach(result::add);
        return result;
    }

    private static ScheduledTask scheduled(Task task) {
        var scheduled = mock(ScheduledTask.class);
        lenient().when(scheduled.getTask()).thenReturn(task);
        return scheduled;
    }

    private static JobExecutionEntity row(String jobName, JobExecutionStatus status, Instant startedAt) {
        var row = new JobExecutionEntity();
        row.setId(UUID.randomUUID());
        row.setJobName(jobName);
        row.setStatus(status);
        row.setStartedAt(startedAt);
        return row;
    }

    public static class SampleLockedJob {
        @Scheduled(fixedDelayString = "PT5M")
        @SchedulerLock(name = "sampleLockedJob", lockAtMostFor = "${test.lock-at-most}")
        public void run() {
            // sample
        }
    }

    public static class SampleCronJob {
        @Scheduled(cron = "0 0 * * * *")
        public void run() {
            // sample
        }
    }
}
