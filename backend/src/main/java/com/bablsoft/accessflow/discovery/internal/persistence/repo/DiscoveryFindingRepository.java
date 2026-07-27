package com.bablsoft.accessflow.discovery.internal.persistence.repo;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryFindingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiscoveryFindingRepository extends JpaRepository<DiscoveryFindingEntity, UUID> {

    Page<DiscoveryFindingEntity> findAllByDatasourceIdAndOrganizationId(UUID datasourceId,
                                                                        UUID organizationId,
                                                                        Pageable pageable);

    Page<DiscoveryFindingEntity> findAllByDatasourceIdAndOrganizationIdAndStatus(
            UUID datasourceId, UUID organizationId, DiscoveryFindingStatus status,
            Pageable pageable);

    long countByDatasourceIdAndOrganizationIdAndStatus(UUID datasourceId, UUID organizationId,
                                                       DiscoveryFindingStatus status);

    Optional<DiscoveryFindingEntity> findByIdAndDatasourceIdAndOrganizationId(
            UUID id, UUID datasourceId, UUID organizationId);

    /** All findings of a datasource — the scan's in-memory upsert index (bounded by the caps). */
    List<DiscoveryFindingEntity> findAllByDatasourceIdAndOrganizationId(UUID datasourceId,
                                                                        UUID organizationId);

    /**
     * Natural-key lookup matching the {@code uq_discovery_finding} unique index. JPQL derived
     * queries treat {@code schemaName = null} as no-match, so the NULL case is explicit.
     */
    @Query("""
            select f from DiscoveryFindingEntity f
            where f.organizationId = :orgId and f.datasourceId = :dsId
              and ((:schemaName is null and f.schemaName is null) or f.schemaName = :schemaName)
              and f.tableName = :tableName and f.columnName = :columnName
              and f.classification = :classification and f.detector = :detector""")
    Optional<DiscoveryFindingEntity> findByNaturalKey(@Param("orgId") UUID organizationId,
                                                      @Param("dsId") UUID datasourceId,
                                                      @Param("schemaName") String schemaName,
                                                      @Param("tableName") String tableName,
                                                      @Param("columnName") String columnName,
                                                      @Param("classification")
                                                      DataClassification classification,
                                                      @Param("detector") DiscoveryDetector detector);
}
