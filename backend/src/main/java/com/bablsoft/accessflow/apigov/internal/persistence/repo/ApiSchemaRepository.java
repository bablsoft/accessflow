package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiSchemaRepository extends JpaRepository<ApiSchemaEntity, UUID> {

    List<ApiSchemaEntity> findByConnectorIdOrderByCreatedAtDesc(UUID connectorId);

    Optional<ApiSchemaEntity> findFirstByConnectorIdOrderByCreatedAtDesc(UUID connectorId);

    Optional<ApiSchemaEntity> findByIdAndConnectorId(UUID id, UUID connectorId);

    void deleteByConnectorId(UUID connectorId);

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
