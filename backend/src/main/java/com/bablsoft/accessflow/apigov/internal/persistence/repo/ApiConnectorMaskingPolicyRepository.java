package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorMaskingPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiConnectorMaskingPolicyRepository
        extends JpaRepository<ApiConnectorMaskingPolicyEntity, UUID> {

    List<ApiConnectorMaskingPolicyEntity> findAllByOrganizationIdAndConnectorIdOrderByCreatedAt(
            UUID organizationId, UUID connectorId);

    List<ApiConnectorMaskingPolicyEntity> findAllByOrganizationIdAndConnectorIdAndEnabledTrue(
            UUID organizationId, UUID connectorId);

    Optional<ApiConnectorMaskingPolicyEntity> findByIdAndOrganizationIdAndConnectorId(
            UUID id, UUID organizationId, UUID connectorId);
}
