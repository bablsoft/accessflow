package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRoutingPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApiRoutingPolicyRepository extends JpaRepository<ApiRoutingPolicyEntity, UUID> {

    List<ApiRoutingPolicyEntity> findByOrganizationIdAndEnabledTrueOrderByPriorityAsc(UUID organizationId);
}
