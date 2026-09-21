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

    boolean existsByPipelineIdAndSortOrder(UUID pipelineId, int sortOrder);

    boolean existsByPipelineIdAndSortOrderAndIdNot(UUID pipelineId, int sortOrder, UUID id);

    /** Highest {@code sort_order} on the pipeline, or null when it has no environments yet. */
    @Query("select max(e.sortOrder) from DeploymentEnvironmentEntity e where e.pipelineId = :pipelineId")
    Integer findMaxSortOrderByPipelineId(@Param("pipelineId") UUID pipelineId);

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
