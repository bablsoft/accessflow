package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import com.bablsoft.accessflow.scheduling.internal.persistence.entity.JobExecutionEntity;
import com.bablsoft.accessflow.scheduling.internal.persistence.repo.JobExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobExecutionRecorderTest {

    private static final Instant START = Instant.parse("2026-09-23T10:00:00Z");

    @Mock JobExecutionRepository repository;

    private JobExecutionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new JobExecutionRecorder(repository, Clock.fixed(START, ZoneOffset.UTC));
    }

    @Test
    void openSavesRunningRowWithInstanceId() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var id = recorder.open("QueryTimeoutJob", "queryTimeoutJob");

        var captor = ArgumentCaptor.forClass(JobExecutionEntity.class);
        verify(repository).save(captor.capture());
        var row = captor.getValue();
        assertThat(row.getId()).isEqualTo(id);
        assertThat(row.getJobName()).isEqualTo("QueryTimeoutJob");
        assertThat(row.getLockName()).isEqualTo("queryTimeoutJob");
        assertThat(row.getStatus()).isEqualTo(JobExecutionStatus.RUNNING);
        assertThat(row.getStartedAt()).isEqualTo(START);
        assertThat(row.getInstanceId()).isEqualTo(recorder.instanceId()).isNotBlank();
    }

    @Test
    void closeMarksSuccessWithDuration() {
        var row = running(START.minusMillis(1500));
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));

        recorder.close(row.getId(), null);

        assertThat(row.getStatus()).isEqualTo(JobExecutionStatus.SUCCESS);
        assertThat(row.getFinishedAt()).isEqualTo(START);
        assertThat(row.getDurationMs()).isEqualTo(1500L);
        assertThat(row.getErrorClass()).isNull();
        verify(repository).save(row);
    }

    @Test
    void closeMarksFailureAndTruncatesTheMessage() {
        var row = running(START);
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));

        recorder.close(row.getId(), new IllegalStateException("x".repeat(5000)));

        assertThat(row.getStatus()).isEqualTo(JobExecutionStatus.FAILED);
        assertThat(row.getErrorClass()).isEqualTo(IllegalStateException.class.getName());
        assertThat(row.getErrorMessage()).hasSize(JobExecutionRecorder.MAX_ERROR_MESSAGE_LENGTH);
        assertThat(row.getDurationMs()).isZero();
    }

    @Test
    void closeOfVanishedRowIsANoOp() {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        recorder.close(id, null);

        verify(repository, never()).save(any());
    }

    @Test
    void truncateKeepsShortAndNullMessages() {
        assertThat(JobExecutionRecorder.truncate(null)).isNull();
        assertThat(JobExecutionRecorder.truncate("boom")).isEqualTo("boom");
    }

    @Test
    void instanceIdPrefersHostnameEnvAndFallsBackToTheHost() {
        assertThat(JobExecutionRecorder.resolveInstanceId(" pod-7 ")).isEqualTo("pod-7");
        assertThat(JobExecutionRecorder.resolveInstanceId(null)).isNotBlank();
        assertThat(JobExecutionRecorder.resolveInstanceId("  ")).isNotBlank();
    }

    private static JobExecutionEntity running(Instant startedAt) {
        var row = new JobExecutionEntity();
        row.setId(UUID.randomUUID());
        row.setJobName("Job");
        row.setStartedAt(startedAt);
        row.setStatus(JobExecutionStatus.RUNNING);
        return row;
    }
}
