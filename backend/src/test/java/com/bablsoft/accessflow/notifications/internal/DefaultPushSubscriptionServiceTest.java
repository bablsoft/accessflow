package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.api.PushSubscriptionService.PushSubscriptionCommand;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.PushSubscriptionEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.PushSubscriptionRepository;
import com.bablsoft.accessflow.notifications.internal.push.PushVapidKeyProvider;
import com.bablsoft.accessflow.notifications.internal.push.PushVapidKeyProvider.VapidKeyMaterial;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultPushSubscriptionServiceTest {

    private PushSubscriptionRepository repository;
    private PushVapidKeyProvider vapidKeyProvider;
    private DefaultPushSubscriptionService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(PushSubscriptionRepository.class);
        vapidKeyProvider = mock(PushVapidKeyProvider.class);
        service = new DefaultPushSubscriptionService(repository, vapidKeyProvider);
    }

    private PushSubscriptionCommand command() {
        return new PushSubscriptionCommand(userId, orgId, "https://push/endpoint", "p256", "auth",
                "Firefox");
    }

    @Test
    void subscribeCreatesNewRowWhenEndpointUnknown() {
        when(repository.findByEndpoint("https://push/endpoint")).thenReturn(Optional.empty());

        service.subscribe(command());

        var captor = ArgumentCaptor.forClass(PushSubscriptionEntity.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getEndpoint()).isEqualTo("https://push/endpoint");
        assertThat(saved.getP256dhKey()).isEqualTo("p256");
        assertThat(saved.getAuthKey()).isEqualTo("auth");
        assertThat(saved.getUserAgent()).isEqualTo("Firefox");
        assertThat(saved.getLastUsedAt()).isNotNull();
    }

    @Test
    void subscribeUpdatesExistingRowAndRehomesUser() {
        var existing = new PushSubscriptionEntity();
        existing.setId(UUID.randomUUID());
        existing.setUserId(UUID.randomUUID());
        existing.setEndpoint("https://push/endpoint");
        when(repository.findByEndpoint("https://push/endpoint")).thenReturn(Optional.of(existing));

        service.subscribe(command());

        var captor = ArgumentCaptor.forClass(PushSubscriptionEntity.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(existing.getId());
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getP256dhKey()).isEqualTo("p256");
    }

    @Test
    void unsubscribeDelegatesToRepository() {
        service.unsubscribe(userId, "https://push/endpoint");

        verify(repository).deleteByUserIdAndEndpoint(userId, "https://push/endpoint");
    }

    @Test
    void vapidPublicKeyComesFromProvider() {
        when(vapidKeyProvider.resolve())
                .thenReturn(new VapidKeyMaterial(null, new byte[0], "PUB-KEY", "mailto:x"));

        assertThat(service.vapidPublicKey()).isEqualTo("PUB-KEY");
    }
}
