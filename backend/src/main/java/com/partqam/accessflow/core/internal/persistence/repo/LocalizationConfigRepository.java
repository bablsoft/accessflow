package com.partqam.accessflow.core.internal.persistence.repo;

import com.partqam.accessflow.core.internal.persistence.entity.LocalizationConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LocalizationConfigRepository extends JpaRepository<LocalizationConfigEntity, UUID> {

    Optional<LocalizationConfigEntity> findByOrganizationId(UUID organizationId);
}
