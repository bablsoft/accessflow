package com.bablsoft.accessflow.lifecycle.internal.persistence.repo;

import com.bablsoft.accessflow.lifecycle.api.LifecycleRunStatus;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleRunEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LifecycleRunRepository extends JpaRepository<LifecycleRunEntity, UUID> {

    boolean existsByPolicyIdAndStatus(UUID policyId, LifecycleRunStatus status);

    @Query("select r from LifecycleRunEntity r where r.organizationId = :organizationId "
            + "and r.createdAt >= :from and r.createdAt < :to "
            + "and (:datasourceId is null or r.datasourceId = :datasourceId) "
            + "order by r.createdAt desc")
    List<LifecycleRunEntity> findForPeriod(@Param("organizationId") UUID organizationId,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to,
                                           @Param("datasourceId") UUID datasourceId,
                                           Pageable pageable);
}
