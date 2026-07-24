package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.QueryEstimateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface QueryEstimateRepository extends JpaRepository<QueryEstimateEntity, UUID> {

    Optional<QueryEstimateEntity> findByQueryRequestId(UUID queryRequestId);
}
