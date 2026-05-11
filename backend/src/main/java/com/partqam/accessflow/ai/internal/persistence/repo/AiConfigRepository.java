package com.partqam.accessflow.ai.internal.persistence.repo;

import com.partqam.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiConfigRepository extends JpaRepository<AiConfigEntity, UUID> {

    List<AiConfigEntity> findAllByOrganizationIdOrderByNameAsc(UUID organizationId);

    Optional<AiConfigEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);

    boolean existsByOrganizationIdAndNameIgnoreCaseAndIdNot(UUID organizationId, String name,
                                                             UUID id);
}
