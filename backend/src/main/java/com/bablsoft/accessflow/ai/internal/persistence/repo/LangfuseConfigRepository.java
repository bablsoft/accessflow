package com.bablsoft.accessflow.ai.internal.persistence.repo;

import com.bablsoft.accessflow.ai.internal.persistence.entity.LangfuseConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LangfuseConfigRepository extends JpaRepository<LangfuseConfigEntity, UUID> {

    Optional<LangfuseConfigEntity> findByOrganizationId(UUID organizationId);
}
