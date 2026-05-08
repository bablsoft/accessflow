package com.partqam.accessflow.notifications.internal;

import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.persistence.entity.UserNotificationEntity;
import com.partqam.accessflow.notifications.internal.persistence.repo.UserNotificationRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultUserNotificationLookupServiceTest {

    @Test
    void findByIdMapsEntityToView() {
        var repo = mock(UserNotificationRepository.class);
        var service = new DefaultUserNotificationLookupService(repo);
        var id = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var entity = new UserNotificationEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setOrganizationId(orgId);
        entity.setEventType(NotificationEventType.QUERY_APPROVED);
        entity.setQueryRequestId(queryId);
        entity.setPayloadJson("{\"a\":1}");
        entity.setRead(true);
        var now = Instant.parse("2026-05-08T10:00:00Z");
        entity.setCreatedAt(now);
        entity.setReadAt(now);
        when(repo.findById(id)).thenReturn(Optional.of(entity));

        var result = service.findById(id);

        assertThat(result).isPresent().get().satisfies(v -> {
            assertThat(v.id()).isEqualTo(id);
            assertThat(v.userId()).isEqualTo(userId);
            assertThat(v.organizationId()).isEqualTo(orgId);
            assertThat(v.eventType()).isEqualTo(NotificationEventType.QUERY_APPROVED);
            assertThat(v.queryRequestId()).isEqualTo(queryId);
            assertThat(v.payloadJson()).isEqualTo("{\"a\":1}");
            assertThat(v.read()).isTrue();
            assertThat(v.createdAt()).isEqualTo(now);
            assertThat(v.readAt()).isEqualTo(now);
        });
    }

    @Test
    void findByIdReturnsEmptyWhenAbsent() {
        var repo = mock(UserNotificationRepository.class);
        var service = new DefaultUserNotificationLookupService(repo);
        var id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        assertThat(service.findById(id)).isEmpty();
    }
}
