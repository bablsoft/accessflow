package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.dashboard.internal.persistence.entity.DashboardDigestSubscriptionEntity;
import com.bablsoft.accessflow.dashboard.internal.persistence.repo.DashboardDigestSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDigestSubscriptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final DashboardDigestSubscriptionRepository repo =
            mock(DashboardDigestSubscriptionRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DefaultDigestSubscriptionService service =
            new DefaultDigestSubscriptionService(repo, clock);

    @Test
    void getDefaultsToDisabledWhenNoRow() {
        when(repo.findByUserId(USER)).thenReturn(Optional.empty());
        var view = service.get(ORG, USER);
        assertThat(view.enabled()).isFalse();
        assertThat(view.lastSentAt()).isNull();
    }

    @Test
    void getReturnsPersistedState() {
        var e = new DashboardDigestSubscriptionEntity();
        e.setEnabled(true);
        e.setLastSentAt(NOW);
        when(repo.findByUserId(USER)).thenReturn(Optional.of(e));
        var view = service.get(ORG, USER);
        assertThat(view.enabled()).isTrue();
        assertThat(view.lastSentAt()).isEqualTo(NOW);
    }

    @Test
    void setCreatesRowWhenAbsent() {
        when(repo.findByUserId(USER)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.set(ORG, USER, true);

        var captor = ArgumentCaptor.forClass(DashboardDigestSubscriptionEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER);
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(ORG);
        assertThat(captor.getValue().isEnabled()).isTrue();
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(view.enabled()).isTrue();
    }

    @Test
    void setUpdatesExistingRow() {
        var e = new DashboardDigestSubscriptionEntity();
        e.setId(UUID.randomUUID());
        e.setUserId(USER);
        e.setOrganizationId(ORG);
        e.setEnabled(true);
        e.setCreatedAt(NOW.minusSeconds(100));
        when(repo.findByUserId(USER)).thenReturn(Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.set(ORG, USER, false);

        assertThat(e.isEnabled()).isFalse();
        assertThat(e.getUpdatedAt()).isEqualTo(NOW);
        assertThat(view.enabled()).isFalse();
    }

    @Test
    void setRejectsNullArgs() {
        assertThatThrownBy(() -> service.set(null, USER, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.set(ORG, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
