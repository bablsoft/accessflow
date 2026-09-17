package com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo;

import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceAccountRepository extends JpaRepository<ServiceAccountEntity, UUID> {

    List<ServiceAccountEntity> findAllByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);
}
