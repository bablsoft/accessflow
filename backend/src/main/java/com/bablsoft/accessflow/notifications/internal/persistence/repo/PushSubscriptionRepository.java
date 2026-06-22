package com.bablsoft.accessflow.notifications.internal.persistence.repo;

import com.bablsoft.accessflow.notifications.internal.persistence.entity.PushSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscriptionEntity, UUID> {

    Optional<PushSubscriptionEntity> findByEndpoint(String endpoint);

    Optional<PushSubscriptionEntity> findByUserIdAndEndpoint(UUID userId, String endpoint);

    List<PushSubscriptionEntity> findByUserIdIn(Collection<UUID> userIds);

    void deleteByUserIdAndEndpoint(UUID userId, String endpoint);
}
