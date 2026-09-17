package com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceAccountRepository extends JpaRepository<ServiceAccountEntity, UUID> {

    List<ServiceAccountEntity> findAllByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

    Optional<ServiceAccountEntity> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);

    Page<ServiceAccountEntity> findAllByOrganizationId(UUID organizationId, Pageable pageable);

    Page<ServiceAccountEntity> findAllByOrganizationIdAndManagedBy(UUID organizationId,
                                                                    ServiceAccountSource managedBy,
                                                                    Pageable pageable);
}
