package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.workflow.internal.persistence.entity.DecisionHookEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DecisionHookRepository extends JpaRepository<DecisionHookEntity, UUID> {

    List<DecisionHookEntity> findAllByOrganizationId(UUID organizationId);

    Optional<DecisionHookEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<DecisionHookEntity> findByOrganizationIdAndDatasourceId(UUID organizationId,
                                                                     UUID datasourceId);

    Optional<DecisionHookEntity> findByOrganizationIdAndDatasourceIdIsNull(UUID organizationId);
}
