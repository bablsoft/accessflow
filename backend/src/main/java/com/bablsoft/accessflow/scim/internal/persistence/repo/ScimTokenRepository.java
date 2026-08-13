package com.bablsoft.accessflow.scim.internal.persistence.repo;

import com.bablsoft.accessflow.scim.internal.persistence.entity.ScimTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScimTokenRepository extends JpaRepository<ScimTokenEntity, UUID> {

    List<ScimTokenEntity> findAllByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<ScimTokenEntity> findByTokenHash(String tokenHash);

    Optional<ScimTokenEntity> findByOrganizationIdAndId(UUID organizationId, UUID id);

    boolean existsByOrganizationIdAndName(UUID organizationId, String name);
}
