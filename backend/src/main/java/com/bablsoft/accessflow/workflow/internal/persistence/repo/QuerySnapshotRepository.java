package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySnapshotEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface QuerySnapshotRepository extends JpaRepository<QuerySnapshotEntity, UUID> {

    boolean existsByQueryRequestId(UUID queryRequestId);

    Optional<QuerySnapshotEntity> findByQueryRequestId(UUID queryRequestId);

    Optional<QuerySnapshotEntity> findByQueryRequestIdAndOrganizationId(UUID queryRequestId,
                                                                        UUID organizationId);

    @Query("""
            select s from QuerySnapshotEntity s
            where s.organizationId = :orgId
              and s.executedAt >= :from and s.executedAt < :to
              and (:datasourceId is null or s.datasourceId = :datasourceId)
            order by s.executedAt asc, s.id asc
            """)
    List<QuerySnapshotEntity> findForPeriod(@Param("orgId") UUID orgId,
                                            @Param("from") Instant from,
                                            @Param("to") Instant to,
                                            @Param("datasourceId") UUID datasourceId,
                                            Pageable pageable);

    @Query("""
            select s from QuerySnapshotEntity s
            where s.organizationId = :orgId
              and s.executedAt >= :from and s.executedAt < :to
              and (:datasourceId is null or s.datasourceId = :datasourceId)
              and s.queryType in :queryTypes
            order by s.executedAt asc, s.id asc
            """)
    List<QuerySnapshotEntity> findForPeriodByType(@Param("orgId") UUID orgId,
                                                  @Param("from") Instant from,
                                                  @Param("to") Instant to,
                                                  @Param("datasourceId") UUID datasourceId,
                                                  @Param("queryTypes") Set<QueryType> queryTypes,
                                                  Pageable pageable);
}
