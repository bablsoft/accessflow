package com.partqam.accessflow.notifications.internal.persistence.repo;

import com.partqam.accessflow.notifications.internal.persistence.entity.UserNotificationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserNotificationRepository
        extends JpaRepository<UserNotificationEntity, UUID> {

    Page<UserNotificationEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndReadFalse(UUID userId);

    Optional<UserNotificationEntity> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Transactional
    @Query("update UserNotificationEntity n "
            + "set n.read = true, n.readAt = :now "
            + "where n.userId = :userId and n.read = false")
    int markAllReadForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
