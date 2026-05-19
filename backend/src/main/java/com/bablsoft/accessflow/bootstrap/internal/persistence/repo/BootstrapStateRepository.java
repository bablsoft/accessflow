package com.bablsoft.accessflow.bootstrap.internal.persistence.repo;

import com.bablsoft.accessflow.bootstrap.internal.persistence.entity.BootstrapStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BootstrapStateRepository extends JpaRepository<BootstrapStateEntity, UUID> {

    Optional<BootstrapStateEntity> findByOrganizationIdAndResourceTypeAndResourceId(
            UUID organizationId, String resourceType, UUID resourceId);
}
