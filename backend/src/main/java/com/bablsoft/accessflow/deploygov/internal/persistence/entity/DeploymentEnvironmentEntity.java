package com.bablsoft.accessflow.deploygov.internal.persistence.entity;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deployment_environments")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DeploymentEnvironmentEntity {

    @Id
    private UUID id;

    @Column(name = "pipeline_id", nullable = false)
    private UUID pipelineId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "require_review", nullable = false)
    private boolean requireReview = true;

    /** Per-environment override of the pipeline's approval count; null = use the plan/pipeline. */
    @Column(name = "required_approvals")
    private Integer requiredApprovals;

    /** Per-environment override of the pipeline's review plan; null = use the pipeline's. */
    @Column(name = "review_plan_id")
    private UUID reviewPlanId;

    @Column(name = "allow_break_glass", nullable = false)
    private boolean allowBreakGlass = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
