package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.dashboard.api.DashboardWeeklySummary;
import com.bablsoft.accessflow.dashboard.events.WeeklyDigestReadyEvent;
import com.bablsoft.accessflow.dashboard.internal.persistence.entity.DashboardDigestSubscriptionEntity;
import com.bablsoft.accessflow.dashboard.internal.persistence.repo.DashboardDigestSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyDigestDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-25T00:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final DashboardWeeklySummaryBuilder builder = mock(DashboardWeeklySummaryBuilder.class);
    private final DashboardDigestSubscriptionRepository repo =
            mock(DashboardDigestSubscriptionRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private final WeeklyDigestDispatchService service =
            new WeeklyDigestDispatchService(builder, repo, publisher);

    @Test
    void publishesEventAndStampsLastSent() {
        var sub = new DashboardDigestSubscriptionEntity();
        sub.setId(UUID.randomUUID());
        sub.setUserId(USER);
        sub.setOrganizationId(ORG);
        sub.setEnabled(true);
        when(repo.findById(sub.getId())).thenReturn(Optional.of(sub));
        when(builder.build(ORG, USER, null)).thenReturn(new DashboardWeeklySummary(ORG, USER,
                "u@x.io", "User", LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 29), 5,
                List.of(), List.of(), 2, 1, 3, NOW));

        service.publishDigest(sub.getId(), NOW);

        var event = ArgumentCaptor.forClass(WeeklyDigestReadyEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(USER);
        assertThat(event.getValue().totalQueries()).isEqualTo(5);
        assertThat(event.getValue().pendingApprovals()).isEqualTo(2);
        assertThat(sub.getLastSentAt()).isEqualTo(NOW);
        verify(repo).save(sub);
    }

    @Test
    void noOpWhenSubscriptionVanished() {
        var id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        service.publishDigest(id, NOW);

        verify(publisher, never()).publishEvent(any());
        verify(repo, never()).save(any());
    }
}
