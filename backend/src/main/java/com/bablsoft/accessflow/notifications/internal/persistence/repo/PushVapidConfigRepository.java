package com.bablsoft.accessflow.notifications.internal.persistence.repo;

import com.bablsoft.accessflow.notifications.internal.persistence.entity.PushVapidConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PushVapidConfigRepository extends JpaRepository<PushVapidConfigEntity, UUID> {

    Optional<PushVapidConfigEntity> findFirstByOrderByCreatedAtAsc();
}
