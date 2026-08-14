package com.bablsoft.accessflow.scim.internal.persistence.repo;

import com.bablsoft.accessflow.scim.internal.persistence.entity.ScimConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScimConfigRepository extends JpaRepository<ScimConfigEntity, UUID> {

    Optional<ScimConfigEntity> findByOrganizationId(UUID organizationId);

    boolean existsByOrganizationIdAndEnabledTrue(UUID organizationId);
}
