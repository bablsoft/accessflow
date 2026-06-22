package com.bablsoft.accessflow.ai.internal.scheduled;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyDetectionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BehaviorAnomalyDetectionJobTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:34:56Z");

    private final BehaviorAnomalyDetectionService detectionService =
            mock(BehaviorAnomalyDetectionService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final BehaviorAnomalyDetectionJob job =
            new BehaviorAnomalyDetectionJob(detectionService, clock);

    @Test
    void runDelegatesToServiceWithClockInstant() {
        when(detectionService.refreshAndDetectAll(any())).thenReturn(2);

        job.run();

        var captor = ArgumentCaptor.forClass(Instant.class);
        verify(detectionService).refreshAndDetectAll(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(NOW);
    }

    @Test
    void runLogsButDoesNotThrowWhenNoAnomaliesDetected() {
        when(detectionService.refreshAndDetectAll(any())).thenReturn(0);
        assertThatCode(job::run).doesNotThrowAnyException();
        verify(detectionService).refreshAndDetectAll(any());
    }

    @Test
    void runSwallowsServiceException() {
        when(detectionService.refreshAndDetectAll(any()))
                .thenThrow(new IllegalStateException("boom"));

        assertThatCode(job::run).doesNotThrowAnyException();
        verify(detectionService, times(1)).refreshAndDetectAll(any());
    }
}
