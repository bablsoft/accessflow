package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.api.UserNotificationLookupService;
import com.bablsoft.accessflow.notifications.api.UserNotificationView;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.UserNotificationEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.UserNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultUserNotificationLookupService implements UserNotificationLookupService {

    private final UserNotificationRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<UserNotificationView> findById(UUID notificationId) {
        return repository.findById(notificationId).map(this::toView);
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
