package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.DataBudgetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataBudgetRepository extends JpaRepository<DataBudgetEntity, UUID> {

    List<DataBudgetEntity> findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(
            UUID organizationId, UUID datasourceId);

    List<DataBudgetEntity> findAllByDatasourceIdAndEnabledTrue(UUID datasourceId);

    List<DataBudgetEntity> findAllByOrganizationIdAndEnabledTrue(UUID organizationId);

    Optional<DataBudgetEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
