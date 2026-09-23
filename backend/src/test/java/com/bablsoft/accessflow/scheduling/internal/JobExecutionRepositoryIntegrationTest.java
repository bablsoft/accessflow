package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import com.bablsoft.accessflow.scheduling.internal.config.JobMonitoringProperties;
import com.bablsoft.accessflow.scheduling.internal.persistence.entity.JobExecutionEntity;
import com.bablsoft.accessflow.scheduling.internal.persistence.repo.JobExecutionRepository;
import com.bablsoft.accessflow.scheduling.internal.scheduled.JobExecutionRetentionJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The native and aggregate queries of {@link JobExecutionRepository} against real PostgreSQL. */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class JobExecutionRepositoryIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired JobExecutionRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void latestPerJobPicksTheNewestRowOfEachJob() {
        save("A", JobExecutionStatus.SUCCESS, NOW.minusSeconds(60));
        var newestA = save("A", JobExecutionStatus.FAILED, NOW.minusSeconds(10));
        var onlyB = save("B", JobExecutionStatus.RUNNING, NOW.minusSeconds(30));

        assertThat(repository.findLatestPerJob()).extracting(JobExecutionEntity::getId)
                .containsExactlyInAnyOrder(newestA.getId(), onlyB.getId());
    }

    @Test
    void consecutiveFailuresCountOnlyFailuresAfterTheLastSuccess() {
        save("A", JobExecutionStatus.FAILED, NOW.minusSeconds(100));
        save("A", JobExecutionStatus.SUCCESS, NOW.minusSeconds(90));
        save("A", JobExecutionStatus.FAILED, NOW.minusSeconds(80));
        save("A", JobExecutionStatus.FAILED, NOW.minusSeconds(70));
        save("B", JobExecutionStatus.FAILED, NOW.minusSeconds(60));
        save("C", JobExecutionStatus.SUCCESS, NOW.minusSeconds(50));

        assertThat(countMap(repository.countConsecutiveFailures())).containsExactlyInAnyOrderEntriesOf(
                Map.of("A", 2L, "B", 1L));
    }

    @Test
    void summarizeCountsAndAveragesWithinTheWindow() {
        save("A", JobExecutionStatus.SUCCESS, NOW.minusSeconds(10), 10L);
        save("A", JobExecutionStatus.SUCCESS, NOW.minusSeconds(20), 30L);
        save("A", JobExecutionStatus.FAILED, NOW.minusSeconds(30), 5L);
        save("A", JobExecutionStatus.RUNNING, NOW.minusSeconds(5), null);
        save("A", JobExecutionStatus.SUCCESS, NOW.minus(Duration.ofDays(2)), 1000L);

        var rows = repository.summarizeSince(NOW.minus(Duration.ofHours(24)),
                List.of(JobExecutionStatus.SUCCESS, JobExecutionStatus.FAILED));

        var byStatus = rows.stream().collect(Collectors.toMap(r -> (JobExecutionStatus) r[1], r -> r));
        assertThat(byStatus).containsOnlyKeys(JobExecutionStatus.SUCCESS, JobExecutionStatus.FAILED);
        assertThat(((Number) byStatus.get(JobExecutionStatus.SUCCESS)[2]).longValue()).isEqualTo(2);
        assertThat(((Number) byStatus.get(JobExecutionStatus.SUCCESS)[3]).doubleValue()).isEqualTo(20.0);
        assertThat(((Number) byStatus.get(JobExecutionStatus.FAILED)[2]).longValue()).isEqualTo(1);
    }

    @Test
    void retentionPrunesByAgeAndCapsEachJobWithoutEvictingAnother() {
        // A busy job with 12 recent rows, a daily job with 3 recent rows, and old rows of both.
        for (int i = 0; i < 12; i++) {
            save("Busy", JobExecutionStatus.SUCCESS, NOW.minusSeconds(30L * (i + 1)));
        }
        for (int i = 0; i < 3; i++) {
            save("Daily", JobExecutionStatus.SUCCESS, NOW.minus(Duration.ofDays(i + 1)));
        }
        save("Busy", JobExecutionStatus.FAILED, NOW.minus(Duration.ofDays(30)));
        save("Daily", JobExecutionStatus.FAILED, NOW.minus(Duration.ofDays(30)));

        new JobExecutionRetentionJob(repository,
                new JobMonitoringProperties(true, Duration.ofDays(14), 5, null, null),
                Clock.fixed(NOW, ZoneOffset.UTC)).run();

        var remaining = repository.findAll();
        var perJob = remaining.stream().collect(Collectors.groupingBy(JobExecutionEntity::getJobName,
                Collectors.counting()));
        assertThat(perJob).containsExactlyInAnyOrderEntriesOf(Map.of("Busy", 5L, "Daily", 3L));
        assertThat(remaining).allMatch(row -> row.getStartedAt().isAfter(NOW.minus(Duration.ofDays(14))));
        // The cap keeps the newest rows.
        assertThat(remaining.stream().filter(r -> r.getJobName().equals("Busy"))
                .map(JobExecutionEntity::getStartedAt).min(Instant::compareTo).orElseThrow())
                .isEqualTo(NOW.minusSeconds(150));
    }

    @Test
    void nonPositiveRetentionKeepsOldRows() {
        save("Old", JobExecutionStatus.SUCCESS, NOW.minus(Duration.ofDays(400)));

        new JobExecutionRetentionJob(repository,
                new JobMonitoringProperties(true, Duration.ZERO, 0, null, null),
                Clock.fixed(NOW, ZoneOffset.UTC)).run();

        assertThat(repository.findAll()).hasSize(1);
    }

    private static Map<String, Long> countMap(List<Object[]> rows) {
        var map = new HashMap<String, Long>();
        rows.forEach(r -> map.put((String) r[0], ((Number) r[1]).longValue()));
        return map;
    }

    private JobExecutionEntity save(String job, JobExecutionStatus status, Instant startedAt) {
        return save(job, status, startedAt, null);
    }

    private JobExecutionEntity save(String job, JobExecutionStatus status, Instant startedAt, Long durationMs) {
        var row = new JobExecutionEntity();
        row.setId(UUID.randomUUID());
        row.setJobName(job);
        row.setStatus(status);
        row.setStartedAt(startedAt);
        row.setDurationMs(durationMs);
        return repository.save(row);
    }
}
