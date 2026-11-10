package com.bablsoft.accessflow.deploygov.internal.scheduled;

import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.verification.DeploymentVerificationService;
import com.bablsoft.accessflow.deploygov.internal.verification.VerificationTickResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentVerificationJobTest {

    private static final Instant NOW = Instant.parse("2026-08-24T09:00:00Z");

    private DeploymentRequestRepository requestRepository;
    private DeploymentVerificationService verificationService;
    private DeploymentVerificationJob job;

    @BeforeEach
    void setUp() {
        requestRepository = mock(DeploymentRequestRepository.class);
        verificationService = mock(DeploymentVerificationService.class);
        job = new DeploymentVerificationJob(requestRepository, verificationService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(requestRepository.findVerificationDueIds()).thenReturn(List.of());
    }

    @Test
    void verifiesEveryRunningRequest() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        when(requestRepository.findVerificationDueIds()).thenReturn(List.of(first, second));
        when(verificationService.verifyOne(first)).thenReturn(VerificationTickResult.SAMPLED);
        when(verificationService.verifyOne(second)).thenReturn(VerificationTickResult.BREACHED);

        job.run();

        verify(verificationService).verifyOne(first);
        verify(verificationService).verifyOne(second);
    }

    @Test
    void oneFailingRowDoesNotAbortTheBatch() {
        var bad = UUID.randomUUID();
        var good = UUID.randomUUID();
        when(requestRepository.findVerificationDueIds()).thenReturn(List.of(bad, good));
        when(verificationService.verifyOne(bad)).thenThrow(new IllegalStateException("boom"));
        when(verificationService.verifyOne(good)).thenReturn(VerificationTickResult.PASSED);

        job.run();

        verify(verificationService).verifyOne(good);
    }

    @Test
    void doesNothingWhenNothingIsRunning() {
        job.run();

        verify(verificationService, never()).verifyOne(any());
    }
}
