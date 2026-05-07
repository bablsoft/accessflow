package com.partqam.accessflow.security.internal.persistence.repo;

import com.partqam.accessflow.security.internal.persistence.entity.SamlConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SamlConfigRepository extends JpaRepository<SamlConfigEntity, UUID> {

    Optional<SamlConfigEntity> findByOrganizationId(UUID organizationId);
}
