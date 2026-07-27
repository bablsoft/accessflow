package com.bablsoft.accessflow.discovery.internal.persistence.repo;

import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryScanConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiscoveryScanConfigRepository
        extends JpaRepository<DiscoveryScanConfigEntity, UUID> {

    Optional<DiscoveryScanConfigEntity> findByDatasourceIdAndOrganizationId(UUID datasourceId,
                                                                            UUID organizationId);

    Optional<DiscoveryScanConfigEntity> findByDatasourceId(UUID datasourceId);

    /** Drained by {@code DiscoveryScanJob}; due-filtering happens in Java (WeeklyDigest style). */
    List<DiscoveryScanConfigEntity> findAllByEnabledTrue();
}
