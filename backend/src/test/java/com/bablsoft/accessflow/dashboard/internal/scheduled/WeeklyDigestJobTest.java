package com.bablsoft.accessflow.dashboard.internal.scheduled;

import com.bablsoft.accessflow.dashboard.internal.WeeklyDigestDispatchService;
import com.bablsoft.accessflow.dashboard.internal.config.DashboardProperties;
import com.bablsoft.accessflow.dashboard.internal.persistence.entity.DashboardDigestSubscriptionEntity;
import com.bablsoft.accessflow.dashboard.internal.persistence.repo.DashboardDigestSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyDigestJobTest {

    private static final Instant NOW = Instant.parse("2026-06-25T06:00:00Z");

    private final DashboardDigestSubscriptionRepository repo =
            mock(DashboardDigestSubscriptionRepository.class);
    private final WeeklyDigestDispatchService dispatch = mock(WeeklyDigestDispatchService.class);
    private final DashboardProperties properties =
            new DashboardProperties(new DashboardProperties.WeeklyDigest(null, Duration.ofDays(7)));
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final WeeklyDigestJob job = new WeeklyDigestJob(repo, dispatch, properties, clock);

    private DashboardDigestSubscriptionEntity sub() {
        var s = new DashboardDigestSubscriptionEntity();
        s.setId(UUID.randomUUID());
        s.setUserId(UUID.randomUUID());
        return s;
    }

    @Test
    void dispatchesEachDueSubscriptionUsingPeriodCutoff() {
        var a = sub();
        var b = sub();
        when(repo.findDue(any())).thenReturn(List.of(a, b));

        job.run();

        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).findDue(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(NOW.minus(Duration.ofDays(7)));
        verify(dispatch).publishDigest(a.getId(), NOW);
        verify(dispatch).publishDigest(b.getId(), NOW);
    }

    @Test
    void skipsWhenNoneDue() {
        when(repo.findDue(any())).thenReturn(List.of());
        job.run();
        verify(dispatch, never()).publishDigest(any(), any());
    }

    @Test
    void swallowsPerRowFailureAndContinues() {
        var a = sub();
        var b = sub();
        when(repo.findDue(any())).thenReturn(List.of(a, b));
        doThrow(new RuntimeException("boom")).when(dispatch).publishDigest(eq(a.getId()), any());

        job.run();

        verify(dispatch, times(2)).publishDigest(any(), any());
        verify(dispatch).publishDigest(b.getId(), NOW);
    }
}
