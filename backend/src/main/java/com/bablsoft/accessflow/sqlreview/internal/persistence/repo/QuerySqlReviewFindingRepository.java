package com.bablsoft.accessflow.sqlreview.internal.persistence.repo;

import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.QuerySqlReviewFindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface QuerySqlReviewFindingRepository extends JpaRepository<QuerySqlReviewFindingEntity, UUID> {

    List<QuerySqlReviewFindingEntity> findAllByQueryRequestIdOrderByStatementIndexAscLineNumberAsc(
            UUID queryRequestId);

    /** Bulk delete for re-evaluation; the caller supplies the transaction. */
    @Modifying
    @Query("delete from QuerySqlReviewFindingEntity f where f.queryRequestId = :queryRequestId")
    void deleteAllByQueryRequestId(@Param("queryRequestId") UUID queryRequestId);
}
