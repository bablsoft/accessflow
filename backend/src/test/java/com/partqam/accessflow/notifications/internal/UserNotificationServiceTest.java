package com.partqam.accessflow.notifications.internal;

import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.api.UserNotificationNotFoundException;
import com.partqam.accessflow.notifications.events.UserNotificationCreatedEvent;
import com.partqam.accessflow.notifications.internal.persistence.entity.UserNotificationEntity;
import com.partqam.accessflow.notifications.internal.persistence.repo.UserNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserNotificationServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T12:00:00Z");

    private UserNotificationRepository repository;
    private ApplicationEventPublisher publisher;
    private UserNotificationService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID queryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(UserNotificationRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new UserNotificationService(repository, publisher,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void recordForUsersPersistsRowAndPublishesEventPerRecipient() {
        when(repository.save(any(UserNotificationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var u1 = UUID.randomUUID();
        var u2 = UUID.randomUUID();
        service.recordForUsers(NotificationEventType.QUERY_SUBMITTED,
                Set.of(u1, u2), orgId, queryId, "{\"k\":1}");

        var entityCaptor = ArgumentCaptor.forClass(UserNotificationEntity.class);
        verify(repository, times(2)).save(entityCaptor.capture());
        var saved = entityCaptor.getAllValues();
        assertThat(saved).extracting(UserNotificationEntity::getUserId).containsExactlyInAnyOrder(u1, u2);
        assertThat(saved).allSatisfy(e -> {
            assertThat(e.getOrganizationId()).isEqualTo(orgId);
            assertThat(e.getEventType()).isEqualTo(NotificationEventType.QUERY_SUBMITTED);
            assertThat(e.getQueryRequestId()).isEqualTo(queryId);
            assertThat(e.getPayloadJson()).isEqualTo("{\"k\":1}");
            assertThat(e.isRead()).isFalse();
            assertThat(e.getCreatedAt()).isEqualTo(FIXED_NOW);
            assertThat(e.getId()).isNotNull();
        });

        var eventCaptor = ArgumentCaptor.forClass(UserNotificationCreatedEvent.class);
        verify(publisher, times(2)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(UserNotificationCreatedEvent::userId)
                .containsExactlyInAnyOrder(u1, u2);
    }

    @Test
    void recordForUsersIsNoOpForEmptyRecipients() {
        service.recordForUsers(NotificationEventType.QUERY_SUBMITTED,
                Set.of(), orgId, queryId, "{}");

        verify(repository, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void recordForUsersIsNoOpForNullRecipients() {
        service.recordForUsers(NotificationEventType.QUERY_SUBMITTED,
                null, orgId, queryId, "{}");

        verify(repository, never()).save(any());
    }

    @Test
    void recordForUsersDefaultsBlankPayloadToEmptyObject() {
        when(repository.save(any(UserNotificationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        service.recordForUsers(NotificationEventType.QUERY_APPROVED,
                Set.of(userId), orgId, queryId, "");

        var captor = ArgumentCaptor.forClass(UserNotificationEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).isEqualTo("{}");
    }

    @Test
    void listForUserMapsToView() {
        var entity = entity(true);
        when(repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(entity)));

        var page = service.listForUser(userId, PageRequest.of(0, 20));

        assertThat(page.getContent()).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(entity.getId());
            assertThat(view.userId()).isEqualTo(userId);
            assertThat(view.organizationId()).isEqualTo(orgId);
            assertThat(view.eventType()).isEqualTo(NotificationEventType.QUERY_APPROVED);
            assertThat(view.read()).isTrue();
        });
    }

    @Test
    void unreadCountDelegates() {
        when(repository.countByUserIdAndReadFalse(userId)).thenReturn(7L);
        assertThat(service.unreadCountForUser(userId)).isEqualTo(7L);
    }

    @Test
    void markReadFlipsAndStampsReadAt() {
        var entity = entity(false);
        when(repository.findByIdAndUserId(entity.getId(), userId)).thenReturn(Optional.of(entity));

        service.markRead(entity.getId(), userId);

        assertThat(entity.isRead()).isTrue();
        assertThat(entity.getReadAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void markReadIsIdempotentWhenAlreadyRead() {
        var entity = entity(true);
        var originalReadAt = entity.getReadAt();
        when(repository.findByIdAndUserId(entity.getId(), userId)).thenReturn(Optional.of(entity));

        service.markRead(entity.getId(), userId);

        assertThat(entity.isRead()).isTrue();
        assertThat(entity.getReadAt()).isEqualTo(originalReadAt);
    }

    @Test
    void markReadThrowsWhenNotFound() {
        var id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(id, userId))
                .isInstanceOf(UserNotificationNotFoundException.class);
    }

    @Test
    void markAllReadDelegatesWithFixedNow() {
        when(repository.markAllReadForUser(userId, FIXED_NOW)).thenReturn(3);

        assertThat(service.markAllRead(userId)).isEqualTo(3);
        verify(repository).markAllReadForUser(eq(userId), eq(FIXED_NOW));
    }

    @Test
    void deleteRemovesOwnedNotification() {
        var entity = entity(false);
        when(repository.findByIdAndUserId(entity.getId(), userId)).thenReturn(Optional.of(entity));

        service.delete(entity.getId(), userId);

        verify(repository).delete(entity);
    }

    @Test
    void deleteThrowsWhenNotOwned() {
        var id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id, userId))
                .isInstanceOf(UserNotificationNotFoundException.class);
    }

    private UserNotificationEntity entity(boolean read) {
        var e = new UserNotificationEntity();
        e.setId(UUID.randomUUID());
        e.setUserId(userId);
        e.setOrganizationId(orgId);
        e.setEventType(NotificationEventType.QUERY_APPROVED);
        e.setQueryRequestId(queryId);
        e.setPayloadJson("{}");
        e.setRead(read);
        e.setCreatedAt(FIXED_NOW);
        if (read) {
            e.setReadAt(FIXED_NOW);
        }
        return e;
    }
}
