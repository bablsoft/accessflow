package com.bablsoft.accessflow.notifications.internal.persistence.repo;

import com.bablsoft.accessflow.notifications.internal.persistence.entity.UserSlackMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserSlackMappingRepository extends JpaRepository<UserSlackMappingEntity, UUID> {

    Optional<UserSlackMappingEntity> findByOrganizationIdAndSlackUserId(UUID organizationId, String slackUserId);

    Optional<UserSlackMappingEntity> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
