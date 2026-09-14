package com.bablsoft.accessflow.sqlreview.internal.persistence.repo;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.QuerySqlReviewFindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface QuerySqlReviewFindingRepository extends JpaRepository<QuerySqlReviewFindingEntity, UUID> {

    List<QuerySqlReviewFindingEntity> findAllByQueryRequestIdOrderByStatementIndexAscLineNumberAsc(
            UUID queryRequestId);

    List<QuerySqlReviewFindingEntity> findAllByQueryRequestIdInOrderByStatementIndexAscLineNumberAsc(
            Collection<UUID> queryRequestIds);

    List<QuerySqlReviewFindingEntity> findAllByRequestGroupItemIdInOrderByStatementIndexAscLineNumberAsc(
            Collection<UUID> requestGroupItemIds);

    /**
     * Blocking-finding count per query, for the reviewer queue (#864). The severity is bound rather
     * than written as a JPQL enum literal — the column is a PostgreSQL enum type.
     */
    @Query("""
            select f.queryRequestId, count(f)
            from QuerySqlReviewFindingEntity f
            where f.queryRequestId in :queryRequestIds and f.severity = :severity
            group by f.queryRequestId
            """)
    List<Object[]> countByQueryRequestIdsAndSeverity(
            @Param("queryRequestIds") Collection<UUID> queryRequestIds,
            @Param("severity") SqlReviewSeverity severity);

    /** Bulk delete for re-evaluation; the caller supplies the transaction. */
    @Modifying
    @Query("delete from QuerySqlReviewFindingEntity f where f.queryRequestId = :queryRequestId")
    void deleteAllByQueryRequestId(@Param("queryRequestId") UUID queryRequestId);

    /** Bulk delete for re-evaluation of a request-group member; the caller supplies the transaction. */
    @Modifying
    @Query("delete from QuerySqlReviewFindingEntity f where f.requestGroupItemId = :requestGroupItemId")
    void deleteAllByRequestGroupItemId(@Param("requestGroupItemId") UUID requestGroupItemId);
}
