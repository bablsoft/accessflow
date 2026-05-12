package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.CustomJdbcDriverEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomJdbcDriverRepository extends JpaRepository<CustomJdbcDriverEntity, UUID> {

    List<CustomJdbcDriverEntity> findAllByOrganization_IdOrderByCreatedAtDesc(UUID organizationId);

    Optional<CustomJdbcDriverEntity> findByIdAndOrganization_Id(UUID id, UUID organizationId);

    Optional<CustomJdbcDriverEntity> findByOrganization_IdAndJarSha256(UUID organizationId,
                                                                       String jarSha256);

    long countByOrganization_Id(UUID organizationId);
}
