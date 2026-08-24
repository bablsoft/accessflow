package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentEnvironmentRepository
        extends JpaRepository<DeploymentEnvironmentEntity, UUID> {

    List<DeploymentEnvironmentEntity> findByPipelineIdOrderBySortOrderAscNameAsc(UUID pipelineId);

    boolean existsByPipelineIdAndName(UUID pipelineId, String name);

    /** Trigger resolution: a CI job names its environment, it does not know the id. */
    Optional<DeploymentEnvironmentEntity> findByPipelineIdAndNameIgnoreCase(UUID pipelineId, String name);

    /**
     * Resolves an environment <em>name</em> to every matching environment id across the org's
     * pipelines, for the request list filter. deploygov entities carry no JPA associations, so the
     * pipeline join is spelled out.
     */
    @Query("""
            select e.id from DeploymentEnvironmentEntity e, DeploymentPipelineEntity p
            where e.pipelineId = p.id and p.organizationId = :organizationId
              and lower(e.name) = lower(:name)
            """)
    List<UUID> findIdsByOrganizationIdAndNameIgnoreCase(@Param("organizationId") UUID organizationId,
                                                        @Param("name") String name);
}
