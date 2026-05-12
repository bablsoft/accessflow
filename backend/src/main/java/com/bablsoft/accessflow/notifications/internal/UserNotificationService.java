package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.api.UserNotificationNotFoundException;
import com.bablsoft.accessflow.notifications.api.UserNotificationView;
import com.bablsoft.accessflow.notifications.events.UserNotificationCreatedEvent;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.UserNotificationEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.UserNotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Persists per-user in-app notifications and exposes the read/write operations the bell-icon
 * inbox needs (list, unread count, mark-read, mark-all-read, delete). Each persisted notification
 * publishes a {@link UserNotificationCreatedEvent} so the realtime module can fan it out over the
 * WebSocket.
 */
@Service
public class UserNotificationService {

    private final UserNotificationRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    UserNotificationService(UserNotificationRepository repository,
                            ApplicationEventPublisher eventPublisher) {
        this(repository, eventPublisher, Clock.systemUTC());
    }

    UserNotificationService(UserNotificationRepository repository,
                            ApplicationEventPublisher eventPublisher,
                            Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public void recordForUsers(NotificationEventType eventType,
                               Set<UUID> recipientUserIds,
                               UUID organizationId,
                               UUID queryRequestId,
                               String payloadJson) {
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            return;
        }
        for (UUID userId : recipientUserIds) {
            var entity = new UserNotificationEntity();
            entity.setId(UUID.randomUUID());
            entity.setUserId(userId);
            entity.setOrganizationId(organizationId);
            entity.setEventType(eventType);
            entity.setQueryRequestId(queryRequestId);
            entity.setPayloadJson(payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson);
            entity.setRead(false);
            entity.setCreatedAt(Instant.now(clock));
            var saved = repository.save(entity);
            eventPublisher.publishEvent(
                    new UserNotificationCreatedEvent(saved.getId(), saved.getUserId()));
        }
    }

    @Transactional(readOnly = true)
    public Page<UserNotificationView> listForUser(UUID userId, Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::toView);
    }

    @Transactional(readOnly = true)
    public long unreadCountForUser(UUID userId) {
        return repository.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(UUID notificationId, UUID userId) {
        var entity = repository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new UserNotificationNotFoundException(notificationId));
        if (!entity.isRead()) {
            entity.setRead(true);
            entity.setReadAt(Instant.now(clock));
        }
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return repository.markAllReadForUser(userId, Instant.now(clock));
    }

    @Transactional
    public void delete(UUID notificationId, UUID userId) {
        var entity = repository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new UserNotificationNotFoundException(notificationId));
        repository.delete(entity);
    }

    private UserNotificationView toView(UserNotificationEntity entity) {
        return new UserNotificationView(
                entity.getId(),
                entity.getUserId(),
                entity.getOrganizationId(),
                entity.getEventType(),
                entity.getQueryRequestId(),
                entity.getPayloadJson(),
                entity.isRead(),
                entity.getCreatedAt(),
                entity.getReadAt());
    }
}
