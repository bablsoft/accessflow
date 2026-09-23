package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.scheduling.api.DistributedLockService;
import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import com.bablsoft.accessflow.scheduling.internal.persistence.entity.JobExecutionEntity;
import com.bablsoft.accessflow.scheduling.internal.persistence.repo.JobExecutionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The recorder against the real advice chain — ShedLock's Redis-backed method proxy, Spring's
 * transaction advice and the recording advisor on one bean — rather than a hand-built proxy.
 * The job methods are called directly, exactly as the scheduler would. The one-hour initial delay
 * keeps the scheduler from firing them itself (Spring Modulith's moments auto-configuration
 * registers a scheduled-annotation processor even with accessflow.scheduling.enabled=false).
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ScheduledJobExecutionIntegrationTest {

    static final String MARKER_JOB = "TransactionMarker";

    @Autowired SucceedingTestJob succeedingJob;
    @Autowired FailingTransactionalTestJob failingJob;
    @Autowired JobExecutionRepository repository;
    @Autowired DistributedLockService lockService;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void jobBeansAreProxied() {
        assertThat(AopUtils.isAopProxy(succeedingJob)).isTrue();
    }

    @Test
    void successfulRunIsRecordedAsSuccess() {
        succeedingJob.run();

        var rows = repository.findAll();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getJobName()).isEqualTo("SucceedingTestJob");
            assertThat(row.getLockName()).isEqualTo("succeedingTestJob");
            assertThat(row.getStatus()).isEqualTo(JobExecutionStatus.SUCCESS);
            assertThat(row.getFinishedAt()).isNotNull();
            assertThat(row.getDurationMs()).isNotNull().isNotNegative();
            assertThat(row.getInstanceId()).isNotBlank();
        });
    }

    @Test
    void failedRunSurvivesTheJobsOwnRollback() {
        assertThatThrownBy(failingJob::run).isInstanceOf(IllegalStateException.class).hasMessage("job exploded");

        var rows = repository.findAll();
        // The marker the job wrote inside its own transaction was rolled back; the record was not.
        assertThat(rows).noneMatch(row -> MARKER_JOB.equals(row.getJobName()));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getJobName()).isEqualTo("FailingTransactionalTestJob");
            assertThat(row.getStatus()).isEqualTo(JobExecutionStatus.FAILED);
            assertThat(row.getErrorClass()).isEqualTo(IllegalStateException.class.getName());
            assertThat(row.getErrorMessage()).isEqualTo("job exploded");
        });
    }

    @Test
    void lockSkippedTickWritesNoRow() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var holder = Executors.newVirtualThreadPerTaskExecutor()) {
            holder.execute(() -> lockService.runLocked("succeedingTestJob", Duration.ofMinutes(1), () -> {
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            succeedingJob.run();

            assertThat(succeedingJob.runs()).isZero();
            assertThat(repository.findAll()).isEmpty();
            release.countDown();
        }
    }

    @TestConfiguration
    static class TestJobs {

        @Bean
        SucceedingTestJob succeedingTestJob() {
            return new SucceedingTestJob();
        }

        @Bean
        FailingTransactionalTestJob failingTransactionalTestJob(JobExecutionRepository repository) {
            return new FailingTransactionalTestJob(repository);
        }
    }

    static class SucceedingTestJob {
        int runs;

        @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
        @SchedulerLock(name = "succeedingTestJob", lockAtMostFor = "PT1M", lockAtLeastFor = "PT0S")
        public void run() {
            runs++;
        }

        public int runs() {
            return runs;
        }
    }

    static class FailingTransactionalTestJob {
        private final JobExecutionRepository repository;

        FailingTransactionalTestJob(JobExecutionRepository repository) {
            this.repository = repository;
        }

        @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
        @SchedulerLock(name = "failingTransactionalTestJob", lockAtMostFor = "PT1M", lockAtLeastFor = "PT0S")
        @Transactional
        public void run() {
            var marker = new JobExecutionEntity();
            marker.setId(UUID.randomUUID());
            marker.setJobName(MARKER_JOB);
            marker.setStartedAt(Instant.now());
            marker.setStatus(JobExecutionStatus.SUCCESS);
            repository.saveAndFlush(marker);
            throw new IllegalStateException("job exploded");
        }
    }
}
