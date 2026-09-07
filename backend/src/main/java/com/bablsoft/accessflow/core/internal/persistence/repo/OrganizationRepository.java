package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {

    Optional<OrganizationEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** Onboarding-domain hints (AF-898); false for a missing organization. */
    boolean existsByIdAndGovernsApisTrue(UUID id);

    boolean existsByIdAndGovernsDeploymentsTrue(UUID id);
}
