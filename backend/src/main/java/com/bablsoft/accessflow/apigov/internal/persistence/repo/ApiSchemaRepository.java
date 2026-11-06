package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiSchemaRepository extends JpaRepository<ApiSchemaEntity, UUID> {

    List<ApiSchemaEntity> findByConnectorIdOrderByCreatedAtDesc(UUID connectorId);

    Optional<ApiSchemaEntity> findFirstByConnectorIdOrderByCreatedAtDesc(UUID connectorId);

    Optional<ApiSchemaEntity> findByIdAndConnectorId(UUID id, UUID connectorId);

    /** Row-locked read that serializes revision accepts on one schema. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ApiSchemaEntity s where s.id = :id and s.connectorId = :connectorId")
    Optional<ApiSchemaEntity> findByIdAndConnectorIdForUpdate(@Param("id") UUID id,
                                                              @Param("connectorId") UUID connectorId);

    void deleteByConnectorId(UUID connectorId);

    /**
     * Stamps the refresh outcome without a whole-row UPDATE, so a refresh racing an admin accept or
     * filter edit can never write back a stale catalog (the row carries no {@code @Version}).
     */
    @Modifying(flushAutomatically = true)
    @Query("update ApiSchemaEntity s set s.lastRefreshAt = :at, s.lastRefreshError = :error where s.id = :id")
    int updateRefreshOutcome(@Param("id") UUID id, @Param("at") Instant at, @Param("error") String error);

    /** {@code sourceUrl} schemas opted into the scheduled refresh whose connector is active (#893). */
    @Query("""
            select new com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRefreshCandidate(
                s.id, s.connectorId, c.organizationId, s.refreshIntervalMinutes, s.lastRefreshAt)
            from ApiSchemaEntity s, ApiConnectorEntity c
            where c.id = s.connectorId and c.active = true
              and s.sourceUrl is not null and s.refreshIntervalMinutes is not null
            order by s.lastRefreshAt asc nulls first
            """)
    List<ApiSchemaRefreshCandidate> findRefreshCandidates();
}
