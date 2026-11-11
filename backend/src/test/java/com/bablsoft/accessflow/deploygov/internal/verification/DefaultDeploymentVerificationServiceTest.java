package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationConfig;
import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheckKind;
import com.bablsoft.accessflow.deploygov.api.VerificationStatus;
import com.bablsoft.accessflow.deploygov.internal.DeploymentRequestStateService;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentVerificationEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentVerificationRepository;
import com.bablsoft.accessflow.deploygov.internal.verification.DeploymentVerificationConfigCodec.VerificationConfigParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultDeploymentVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final String JSON = "{\"snapshot\":true}";
    private static final HttpHealthCheck HEALTH =
            new HttpHealthCheck("health", "https://svc.example.com/health", 200, null);
    private static final HttpHealthCheck READY =
            new HttpHealthCheck("ready", "https://svc.example.com/ready", 200, null);

    private DeploymentRequestRepository requestRepository;
    private DeploymentVerificationRepository verificationRepository;
    private DeploymentRequestStateService stateService;
    private DeploymentVerificationConfigCodec codec;
    private VerificationCheckEvaluators evaluators;
    private DefaultDeploymentVerificationService service;
    private DeploymentRequestEntity request;

    @BeforeEach
    void setUp() {
        requestRepository = mock(DeploymentRequestRepository.class);
        verificationRepository = mock(DeploymentVerificationRepository.class);
        stateService = mock(DeploymentRequestStateService.class);
        codec = mock(DeploymentVerificationConfigCodec.class);
        evaluators = mock(VerificationCheckEvaluators.class);
        var transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new DefaultDeploymentVerificationService(requestRepository,
                verificationRepository, stateService, codec, evaluators,
                Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);

        request = new DeploymentRequestEntity();
        request.setId(UUID.randomUUID());
        request.setOrganizationId(UUID.randomUUID());
        request.setPipelineId(UUID.randomUUID());
        request.setStatus(QueryStatus.EXECUTED);
        request.setVerificationStatus(VerificationStatus.RUNNING);
        request.setVerificationConfig(JSON);
        request.setVerificationStartedAt(NOW.minus(Duration.ofMinutes(2)));
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(codec.fromJson(JSON)).thenReturn(config(3, HEALTH, READY));
        when(evaluators.evaluate(HEALTH)).thenReturn(passed(HEALTH));
        when(evaluators.evaluate(READY)).thenReturn(passed(READY));
        when(stateService.markVerificationFailed(any(), any(), anyString())).thenReturn(true);
        when(stateService.markVerificationPassed(any())).thenReturn(true);
    }

    @Test
    void persistsOneSamplePerCheckAndStampsLastVerifiedAt() {
        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SAMPLED);

        var saved = ArgumentCaptor.forClass(DeploymentVerificationEntity.class);
        verify(verificationRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(DeploymentVerificationEntity::getCheckName)
                .containsExactly("health", "ready");
        var first = saved.getAllValues().getFirst();
        assertThat(first.getDeploymentRequestId()).isEqualTo(request.getId());
        assertThat(first.getOrganizationId()).isEqualTo(request.getOrganizationId());
        assertThat(first.getCheckKind()).isEqualTo(VerificationCheckKind.HTTP_HEALTH);
        assertThat(first.getSampledAt()).isEqualTo(NOW);
        assertThat(first.isPassed()).isTrue();
        assertThat(first.getObservedValue()).isEqualTo("200");
        assertThat(request.getLastVerifiedAt()).isEqualTo(NOW);
        verify(requestRepository).save(request);
        verify(stateService, never()).markVerificationFailed(any(), any(), any());
        verify(stateService, never()).markVerificationPassed(any());
    }

    @Test
    void skipsWhenTheNextSampleIsNotYetDue() {
        request.setLastVerifiedAt(NOW.minusSeconds(30));

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SKIPPED);

        verifyNoInteractions(evaluators, verificationRepository);
    }

    @Test
    void samplesAgainOnceTheIntervalHasElapsed() {
        request.setLastVerifiedAt(NOW.minusSeconds(60));

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SAMPLED);

        verify(evaluators).evaluate(HEALTH);
    }

    @Test
    void skipsARequestThatIsNotARunningVerification() {
        request.setVerificationStatus(VerificationStatus.PASSED);
        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SKIPPED);

        request.setVerificationStatus(VerificationStatus.RUNNING);
        request.setStatus(QueryStatus.FAILED);
        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SKIPPED);

        var missing = UUID.randomUUID();
        when(requestRepository.findById(missing)).thenReturn(Optional.empty());
        assertThat(service.verifyOne(missing)).isEqualTo(VerificationTickResult.SKIPPED);
        verifyNoInteractions(evaluators);
    }

    @Test
    void breachesOnTheCheckThatReachesItsThresholdOnly() {
        when(evaluators.evaluate(READY)).thenReturn(failed(READY));
        when(verificationRepository.countByDeploymentRequestIdAndCheckNameAndPassedFalse(
                request.getId(), "health")).thenReturn(0L);
        when(verificationRepository.countByDeploymentRequestIdAndCheckNameAndPassedFalse(
                request.getId(), "ready")).thenReturn(3L);

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.BREACHED);

        verify(stateService).markVerificationFailed(request.getId(), "ready", "ready: 3/3 failed samples");
        verify(stateService, never()).markVerificationPassed(any());
    }

    @Test
    void belowTheThresholdKeepsSampling() {
        when(evaluators.evaluate(HEALTH)).thenReturn(failed(HEALTH));
        when(verificationRepository.countByDeploymentRequestIdAndCheckNameAndPassedFalse(
                request.getId(), "health")).thenReturn(2L);

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SAMPLED);

        verify(stateService, never()).markVerificationFailed(any(), any(), any());
    }

    @Test
    void aBreachLostToARaceIsReportedAsSkipped() {
        when(verificationRepository.countByDeploymentRequestIdAndCheckNameAndPassedFalse(
                request.getId(), "health")).thenReturn(3L);
        when(stateService.markVerificationFailed(any(), any(), anyString())).thenReturn(false);

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SKIPPED);
    }

    @Test
    void passesAtTheEndOfACleanWindow() {
        request.setVerificationStartedAt(NOW.minus(Duration.ofMinutes(10)));

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.PASSED);

        verify(verificationRepository, times(2)).save(any());
        verify(stateService).markVerificationPassed(request.getId());
    }

    @Test
    void aPassLostToARaceIsReportedAsSkipped() {
        request.setVerificationStartedAt(NOW.minus(Duration.ofMinutes(11)));
        when(stateService.markVerificationPassed(any())).thenReturn(false);

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SKIPPED);
    }

    @Test
    void aBreachOnTheLastSampleWinsOverTheWindowEnd() {
        request.setVerificationStartedAt(NOW.minus(Duration.ofMinutes(10)));
        when(verificationRepository.countByDeploymentRequestIdAndCheckNameAndPassedFalse(
                request.getId(), "health")).thenReturn(3L);

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.BREACHED);

        verify(stateService, never()).markVerificationPassed(any());
    }

    @Test
    void aThrowingEvaluatorIsRecordedAsAFailedSample() {
        when(evaluators.evaluate(HEALTH)).thenThrow(new IllegalStateException("secret body"));

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SAMPLED);

        var saved = ArgumentCaptor.forClass(DeploymentVerificationEntity.class);
        verify(verificationRepository, times(2)).save(saved.capture());
        var health = saved.getAllValues().getFirst();
        assertThat(health.isPassed()).isFalse();
        assertThat(health.getObservedValue()).isNull();
        assertThat(health.getDetail()).isEqualTo("evaluation error: IllegalStateException");
    }

    @Test
    void anUnreadableSnapshotFailsClosed() {
        when(codec.fromJson(JSON)).thenThrow(new VerificationConfigParseException("bad"));

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.BREACHED);

        verify(stateService).markVerificationFailed(eq(request.getId()), isNull(),
                eq(DefaultDeploymentVerificationService.UNREADABLE_CONFIG));
        verifyNoInteractions(evaluators, verificationRepository);
    }

    @Test
    void aMissingSnapshotFailsClosedAndALostRaceIsSkipped() {
        when(codec.fromJson(JSON)).thenReturn(null);
        when(stateService.markVerificationFailed(any(), any(), anyString())).thenReturn(false);

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SKIPPED);

        verify(stateService).markVerificationFailed(eq(request.getId()), isNull(),
                eq(DefaultDeploymentVerificationService.UNREADABLE_CONFIG));
    }

    @Test
    void aRequestThatLeftRunningDuringEvaluationDropsTheTick() {
        var concurrent = new DeploymentRequestEntity();
        concurrent.setId(request.getId());
        concurrent.setStatus(QueryStatus.FAILED);
        concurrent.setVerificationStatus(VerificationStatus.RUNNING);
        when(requestRepository.findByIdForUpdate(request.getId()))
                .thenReturn(Optional.of(concurrent));

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SKIPPED);

        verify(verificationRepository, never()).save(any());
        verify(requestRepository, never()).save(any());
    }

    @Test
    void anOverlappingTickThatAlreadySampledTheRowDropsThisOne() {
        var locked = new DeploymentRequestEntity();
        locked.setId(request.getId());
        locked.setStatus(QueryStatus.EXECUTED);
        locked.setVerificationStatus(VerificationStatus.RUNNING);
        locked.setLastVerifiedAt(NOW.minusSeconds(1));
        when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(locked));

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SKIPPED);

        verify(verificationRepository, never()).save(any());
        verify(stateService, never()).markVerificationFailed(any(), any(), any());
    }

    @Test
    void aRequestDeletedDuringEvaluationDropsTheTick() {
        when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.empty());

        assertThat(service.verifyOne(request.getId())).isEqualTo(VerificationTickResult.SKIPPED);

        verify(verificationRepository, never()).save(any());
    }

    private static DeploymentVerificationConfig config(int threshold, HttpHealthCheck... checks) {
        return new DeploymentVerificationConfig(Duration.ofMinutes(10), Duration.ofMinutes(1),
                threshold, List.of(checks));
    }

    private static VerificationSample passed(HttpHealthCheck check) {
        return new VerificationSample(check.name(), VerificationCheckKind.HTTP_HEALTH, true, "200",
                null);
    }

    private static VerificationSample failed(HttpHealthCheck check) {
        return new VerificationSample(check.name(), VerificationCheckKind.HTTP_HEALTH, false, "503",
                "expected status 200, got 503");
    }
}
