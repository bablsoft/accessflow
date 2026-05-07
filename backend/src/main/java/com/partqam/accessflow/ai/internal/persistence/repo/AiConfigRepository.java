package com.partqam.accessflow.ai.internal.persistence.repo;

import com.partqam.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiConfigRepository extends JpaRepository<AiConfigEntity, UUID> {

    Optional<AiConfigEntity> findByOrganizationId(UUID organizationId);
}
