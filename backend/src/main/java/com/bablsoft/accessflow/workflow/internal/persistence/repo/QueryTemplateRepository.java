package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface QueryTemplateRepository
        extends JpaRepository<QueryTemplateEntity, UUID>,
                JpaSpecificationExecutor<QueryTemplateEntity> {

    @Query("select t from QueryTemplateEntity t where t.organizationId = :organizationId "
            + "and t.ownerId = :ownerId and lower(t.name) = lower(:name)")
    Optional<QueryTemplateEntity> findByOrganizationIdAndOwnerIdAndNameIgnoreCase(
            @Param("organizationId") UUID organizationId,
            @Param("ownerId") UUID ownerId,
            @Param("name") String name);
}
