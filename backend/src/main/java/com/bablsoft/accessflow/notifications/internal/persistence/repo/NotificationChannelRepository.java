package com.bablsoft.accessflow.notifications.internal.persistence.repo;

import com.bablsoft.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannelEntity, UUID> {

    List<NotificationChannelEntity> findAllByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

    List<NotificationChannelEntity> findAllByOrganizationIdAndActiveTrue(UUID organizationId);

    Optional<NotificationChannelEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<NotificationChannelEntity> findAllByOrganizationIdAndIdInAndActiveTrue(
            UUID organizationId, Collection<UUID> ids);
}
