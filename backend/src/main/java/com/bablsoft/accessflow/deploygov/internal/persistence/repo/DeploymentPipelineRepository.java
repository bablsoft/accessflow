package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentPipelineRepository extends JpaRepository<DeploymentPipelineEntity, UUID> {

    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

    Optional<DeploymentPipelineEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Gate resolution (#693): a CI job names its pipeline, it does not know the id. A list, not an
     * {@code Optional} — org-name uniqueness is case-<em>sensitive</em> (V149), so two pipelines
     * differing only by case may legally coexist and an ignore-case single-row finder would throw.
     * The gate service picks the exact-case match first, else the alphabetically first.
     */
    List<DeploymentPipelineEntity> findByOrganizationIdAndNameIgnoreCaseOrderByNameAsc(
            UUID organizationId, String name);

    Page<DeploymentPipelineEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    /** Review-reach resolution (#692): the whole catalog, inactive pipelines included. */
    List<DeploymentPipelineEntity> findAllByOrganizationId(UUID organizationId);
}
