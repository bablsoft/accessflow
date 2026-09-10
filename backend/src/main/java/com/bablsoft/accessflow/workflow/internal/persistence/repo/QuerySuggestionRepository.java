package com.bablsoft.accessflow.workflow.internal.persistence.repo;

import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySuggestionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuerySuggestionRepository extends JpaRepository<QuerySuggestionEntity, UUID> {

    Optional<QuerySuggestionEntity> findByDatasourceIdAndCanonicalHash(UUID datasourceId,
                                                                       String canonicalHash);

    List<QuerySuggestionEntity> findByDatasourceIdOrderByApprovedCountDescLastSubmittedAtDesc(
            UUID datasourceId);

    /**
     * Removes the rows an aggregation pass did not rewrite — a query shape that has dropped out of
     * the lookback window, or that no longer earns a place in the datasource's top slice. Scoped to
     * one datasource, matching the aggregation's unit of work, so a datasource whose pass failed
     * keeps the suggestions it had rather than losing them to another datasource's sweep.
     *
     * <p>{@code flushAutomatically} matters: the aggregation saves its rows through the persistence
     * context and then runs this bulk delete straight against the database, so without a flush the
     * just-written rows would still carry the previous pass's {@code computed_at} and delete
     * themselves.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from QuerySuggestionEntity s
             where s.datasourceId = :datasourceId
               and s.computedAt < :computedBefore
            """)
    int deleteStaleForDatasource(@Param("datasourceId") UUID datasourceId,
                                 @Param("computedBefore") Instant computedBefore);
}
