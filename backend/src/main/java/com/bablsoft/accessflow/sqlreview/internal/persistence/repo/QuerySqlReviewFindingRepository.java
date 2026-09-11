package com.bablsoft.accessflow.sqlreview.internal.persistence.repo;

import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.QuerySqlReviewFindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuerySqlReviewFindingRepository extends JpaRepository<QuerySqlReviewFindingEntity, UUID> {

    List<QuerySqlReviewFindingEntity> findAllByQueryRequestIdOrderByStatementIndexAscLineNumberAsc(
            UUID queryRequestId);

    void deleteAllByQueryRequestId(UUID queryRequestId);
}
