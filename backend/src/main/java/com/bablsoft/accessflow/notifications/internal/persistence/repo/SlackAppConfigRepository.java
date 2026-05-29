package com.bablsoft.accessflow.notifications.internal.persistence.repo;

import com.bablsoft.accessflow.notifications.internal.persistence.entity.SlackAppConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SlackAppConfigRepository extends JpaRepository<SlackAppConfigEntity, UUID> {

    Optional<SlackAppConfigEntity> findByOrganizationId(UUID organizationId);

    Optional<SlackAppConfigEntity> findByAppId(String appId);
}
