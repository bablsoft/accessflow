package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecyclePreviewResult;
import com.bablsoft.accessflow.lifecycle.api.LifecycleRunStatus;
import com.bablsoft.accessflow.lifecycle.events.LifecycleScanCompletedEvent;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleRunEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.LifecycleRunRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.RetentionPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionPolicyScanServiceTest {

    @Mock
    private RetentionPolicyRepository policyRepository;
    @Mock
    private LifecycleRunRepository runRepository;
    @Mock
    private LifecyclePreviewCalculator previewCalculator;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RetentionPolicyScanService service;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
        service = new RetentionPolicyScanService(policyRepository, runRepository, previewCalculator,
                eventPublisher, clock);
    }

    private RetentionPolicyEntity policy() {
        var p = new RetentionPolicyEntity();
        p.setId(UUID.randomUUID());
        p.setOrganizationId(UUID.randomUUID());
        p.setDatasourceId(UUID.randomUUID());
        p.setAction(LifecycleAction.HARD_DELETE);
        return p;
    }

    @Test
    void scanAndStage_returnsZeroWhenNoPolicies() {
        when(policyRepository.findAllByEnabledTrue()).thenReturn(List.of());
        assertThat(service.scanAndStage()).isZero();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void scanAndStage_stagesRunWhenEligible() {
        var p = policy();
        when(policyRepository.findAllByEnabledTrue()).thenReturn(List.of(p));
        when(runRepository.existsByPolicyIdAndStatus(p.getId(), LifecycleRunStatus.STAGED))
                .thenReturn(false);
        when(previewCalculator.preview(p)).thenReturn(
                new LifecyclePreviewResult(LifecycleAction.HARD_DELETE, null, 42L, List.of()));

        int staged = service.scanAndStage();

        assertThat(staged).isEqualTo(1);
        verify(runRepository).save(any(LifecycleRunEntity.class));
        var captor = ArgumentCaptor.forClass(LifecycleScanCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().stagedRuns()).isEqualTo(1);
    }

    @Test
    void scanAndStage_skipsWhenAlreadyStaged() {
        var p = policy();
        when(policyRepository.findAllByEnabledTrue()).thenReturn(List.of(p));
        when(runRepository.existsByPolicyIdAndStatus(p.getId(), LifecycleRunStatus.STAGED))
                .thenReturn(true);

        assertThat(service.scanAndStage()).isZero();
        verify(runRepository, never()).save(any());
    }

    @Test
    void scanAndStage_skipsWhenNoEligibleRows() {
        var p = policy();
        when(policyRepository.findAllByEnabledTrue()).thenReturn(List.of(p));
        when(runRepository.existsByPolicyIdAndStatus(p.getId(), LifecycleRunStatus.STAGED))
                .thenReturn(false);
        when(previewCalculator.preview(p)).thenReturn(
                new LifecyclePreviewResult(LifecycleAction.HARD_DELETE, null, 0L, List.of()));

        assertThat(service.scanAndStage()).isZero();
        verify(runRepository, never()).save(any());
    }

    @Test
    void scanAndStage_skipsCronPolicyNotYetDue() {
        var p = policy();
        p.setCronSchedule("0 0 0 * * *"); // daily at midnight
        p.setLastRunAt(Instant.parse("2026-06-29T00:00:00Z")); // ran now; next fire is tomorrow
        when(policyRepository.findAllByEnabledTrue()).thenReturn(List.of(p));
        when(runRepository.existsByPolicyIdAndStatus(p.getId(), LifecycleRunStatus.STAGED))
                .thenReturn(false);

        assertThat(service.scanAndStage()).isZero();
        verify(runRepository, never()).save(any());
        verify(previewCalculator, never()).preview(any());
    }

    @Test
    void scanAndStage_stagesCronPolicyWhenDueAndAdvancesBookkeeping() {
        var p = policy();
        p.setCronSchedule("0 0 0 * * *");
        p.setLastRunAt(Instant.parse("2026-06-27T00:00:00Z")); // next fire (06-28) is due
        when(policyRepository.findAllByEnabledTrue()).thenReturn(List.of(p));
        when(runRepository.existsByPolicyIdAndStatus(p.getId(), LifecycleRunStatus.STAGED))
                .thenReturn(false);
        when(previewCalculator.preview(p)).thenReturn(
                new LifecyclePreviewResult(LifecycleAction.HARD_DELETE, null, 5L, List.of()));

        assertThat(service.scanAndStage()).isEqualTo(1);
        assertThat(p.getLastRunAt()).isEqualTo(Instant.parse("2026-06-29T00:00:00Z"));
        assertThat(p.getNextRunAt()).isEqualTo(Instant.parse("2026-06-30T00:00:00Z"));
        verify(policyRepository).save(p);
        verify(runRepository).save(any(LifecycleRunEntity.class));
    }

    @Test
    void scanAndStage_swallowsPerPolicyFailure() {
        var p = policy();
        when(policyRepository.findAllByEnabledTrue()).thenReturn(List.of(p));
        when(runRepository.existsByPolicyIdAndStatus(p.getId(), LifecycleRunStatus.STAGED))
                .thenThrow(new RuntimeException("boom"));

        assertThat(service.scanAndStage()).isZero();
        verify(eventPublisher).publishEvent(any(LifecycleScanCompletedEvent.class));
    }
}
