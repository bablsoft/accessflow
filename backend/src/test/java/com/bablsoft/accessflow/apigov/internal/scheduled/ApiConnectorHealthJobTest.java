package com.bablsoft.accessflow.apigov.internal.scheduled;

import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthService;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorHealthCandidate;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorHealthCheckRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiConnectorHealthJobTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private ApiConnectorHealthCheckRepository healthCheckRepository;
    @Mock private ApiConnectorHealthService healthService;

    private ApiConnectorHealthJob job;

    @BeforeEach
    void setUp() {
        var properties = new ApigovRequestProperties(1L, 1L, 1L, 1, 1, 3, null);
        job = new ApiConnectorHealthJob(connectorRepository, healthCheckRepository, healthService, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ApiConnectorHealthCandidate candidate(int intervalMinutes, Instant lastProbeAt) {
        return new ApiConnectorHealthCandidate(UUID.randomUUID(), UUID.randomUUID(), intervalMinutes, lastProbeAt);
    }

    @Test
    void probesNeverProbedAndDueConnectorsButNotOnesInsideTheirInterval() {
        var neverProbed = candidate(5, null);
        var due = candidate(5, NOW.minus(Duration.ofMinutes(5)));
        var notDue = candidate(5, NOW.minus(Duration.ofMinutes(4)));
        when(connectorRepository.findHealthCandidates()).thenReturn(List.of(neverProbed, due, notDue));

        job.run();

        verify(healthService).probe(neverProbed.connectorId());
        verify(healthService).probe(due.connectorId());
        verify(healthService, never()).probe(notDue.connectorId());
    }

    @Test
    void noCandidatesProbesNothingButStillPrunes() {
        when(connectorRepository.findHealthCandidates()).thenReturn(List.of());

        job.run();

        verifyNoInteractions(healthService);
        verify(healthCheckRepository).deleteByCheckedAtBefore(NOW.minus(Duration.ofDays(7)));
    }

    @Test
    void oneThrowingConnectorDoesNotStopTheBatch() {
        var failing = candidate(1, null);
        var next = candidate(1, null);
        when(connectorRepository.findHealthCandidates()).thenReturn(List.of(failing, next));
        doThrow(new IllegalStateException("boom")).when(healthService).probe(failing.connectorId());

        job.run();

        verify(healthService).probe(next.connectorId());
        verify(healthCheckRepository).deleteByCheckedAtBefore(NOW.minus(Duration.ofDays(7)));
    }

    @Test
    void retentionFailureIsSwallowed() {
        when(connectorRepository.findHealthCandidates()).thenReturn(List.of());
        when(healthCheckRepository.deleteByCheckedAtBefore(any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(job::run).doesNotThrowAnyException();
    }

    @Test
    void reportsPrunedRows() {
        when(connectorRepository.findHealthCandidates()).thenReturn(List.of());
        when(healthCheckRepository.deleteByCheckedAtBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(4);

        assertThatCode(job::run).doesNotThrowAnyException();
    }
}
