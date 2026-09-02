package com.bablsoft.accessflow.deploygov.internal.persistence.repo;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRequestRepository extends JpaRepository<DeploymentRequestEntity, UUID>,
        JpaSpecificationExecutor<DeploymentRequestEntity> {

    /** The gate lookup: every request for a (pipeline, environment, version), newest first. */
    List<DeploymentRequestEntity> findByPipelineIdAndEnvironmentIdAndVersionOrderByCreatedAtDesc(
            UUID pipelineId, UUID environmentId, String version);

    Optional<DeploymentRequestEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * Idempotent-trigger lookup — the exact tuple of the partial unique index
     * {@code uq_deployment_requests_trigger_idem} (V150), so a replayed CI run resolves to the
     * request it already created.
     */
    Optional<DeploymentRequestEntity> findByPipelineIdAndEnvironmentIdAndVersionAndExternalRunId(
            UUID pipelineId, UUID environmentId, String version, String externalRunId);

    /** History timeline (#742): every request for the environment, newest first. */
    Page<DeploymentRequestEntity> findByPipelineIdAndEnvironmentIdOrderByCreatedAtDesc(
            UUID pipelineId, UUID environmentId, Pageable pageable);

    /** History timeline (#742), narrowed to one status. */
    Page<DeploymentRequestEntity> findByPipelineIdAndEnvironmentIdAndStatusOrderByCreatedAtDesc(
            UUID pipelineId, UUID environmentId, QueryStatus status, Pageable pageable);

    /**
     * Grouped drift projection (#742): each version successfully deployed on the pipeline (an
     * {@code EXECUTED} request whose outcome is null or {@code SUCCEEDED}) with the last instant
     * it executed anywhere. Enum values are bound as parameters — a JPQL enum literal fails at
     * runtime against the PostgreSQL enum columns (see {@code findStalePendingReviewIds}).
     * Backed by the partial index {@code idx_deployment_requests_executed_versions} (V157).
     */
    @Query("""
            select new com.bablsoft.accessflow.deploygov.internal.persistence.repo\
            .DeploymentVersionExecution(r.version, max(r.executedAt))
            from DeploymentRequestEntity r
            where r.pipelineId = :pipelineId
              and r.status = :executed
              and (r.outcome is null or r.outcome = :succeeded)
              and r.executedAt is not null
            group by r.version
            """)
    List<DeploymentVersionExecution> findSuccessfulVersionExecutions(
            @Param("pipelineId") UUID pipelineId, @Param("executed") QueryStatus executed,
            @Param("succeeded") DeploymentOutcome succeeded);

    /**
     * Timeout scan (#692): {@code PENDING_REVIEW} requests older than their resolved review plan's
     * {@code approval_timeout_hours} — the environment's plan override wins over the pipeline's,
     * same precedence as routing. A request whose resolved plan is absent never times out,
     * mirroring the core query job. Native SQL with an explicit {@code ::query_status} cast — a
     * JPQL enum literal makes Hibernate emit a cast to a non-existent "querystatus" type.
     */
    @Query(value = """
            SELECT r.id FROM deployment_requests r
            JOIN deployment_environments e ON e.id = r.environment_id
            JOIN deployment_pipelines p ON p.id = r.pipeline_id
            JOIN review_plans rp ON rp.id = COALESCE(e.review_plan_id, p.review_plan_id)
            WHERE r.status = 'PENDING_REVIEW'::query_status
              AND r.created_at + (rp.approval_timeout_hours || ' hours')::interval < :now
            """, nativeQuery = true)
    List<UUID> findStalePendingReviewIds(@Param("now") Instant now);

    /**
     * Release-announcement scan (#693): {@code APPROVED} requests not yet announced whose
     * scheduled moment (if any) has passed. Backed by the partial index
     * {@code idx_deployment_requests_release_scan} (V154); the per-row freeze evaluation happens in
     * {@code markReleasable}, not here. Native for the same {@code ::query_status} reason as above.
     */
    @Query(value = """
            SELECT r.id FROM deployment_requests r
            WHERE r.status = 'APPROVED'::query_status
              AND r.release_notified_at IS NULL
              AND (r.scheduled_for IS NULL OR r.scheduled_for <= :now)
            """, nativeQuery = true)
    List<UUID> findReleasableCandidateIds(@Param("now") Instant now);
}
