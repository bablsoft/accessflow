package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApiConnectorRepository extends JpaRepository<ApiConnectorEntity, UUID> {

    Optional<ApiConnectorEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<ApiConnectorEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    boolean existsByOrganizationIdAndName(UUID organizationId, String name);
}
