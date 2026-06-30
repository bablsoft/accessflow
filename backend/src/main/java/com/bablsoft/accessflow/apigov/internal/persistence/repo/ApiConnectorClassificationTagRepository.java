package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorClassificationTagEntity;
import com.bablsoft.accessflow.core.api.DataClassification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiConnectorClassificationTagRepository
        extends JpaRepository<ApiConnectorClassificationTagEntity, UUID> {

    List<ApiConnectorClassificationTagEntity> findAllByOrganizationIdAndConnectorIdOrderByCreatedAt(
            UUID organizationId, UUID connectorId);

    List<ApiConnectorClassificationTagEntity> findAllByOrganizationIdAndConnectorId(
            UUID organizationId, UUID connectorId);

    Optional<ApiConnectorClassificationTagEntity> findByIdAndOrganizationIdAndConnectorId(
            UUID id, UUID organizationId, UUID connectorId);

    boolean existsByOrganizationIdAndConnectorIdAndOperationIdAndFieldRefAndClassification(
            UUID organizationId, UUID connectorId, String operationId, String fieldRef,
            DataClassification classification);

    boolean existsByOrganizationIdAndConnectorIdAndOperationIdIsNullAndFieldRefAndClassification(
            UUID organizationId, UUID connectorId, String fieldRef, DataClassification classification);

    boolean existsByOrganizationIdAndConnectorIdAndMatcherTypeAndOperationIdAndFieldRef(
            UUID organizationId, UUID connectorId, ApiMaskingMatcherType matcherType,
            String operationId, String fieldRef);
}
