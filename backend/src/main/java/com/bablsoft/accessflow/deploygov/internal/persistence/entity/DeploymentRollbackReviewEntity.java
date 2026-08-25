package com.bablsoft.accessflow.deploygov.internal.persistence.entity;

import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

/**
 * A rollback follow-up review (#693): opened when a {@code ROLLED_BACK} outcome lands on an
 * environment with {@code require_review = true}, acknowledged by a reviewer — never the
 * deployment's submitter. Scope/actor ids are bare UUIDs (no FK), like
 * {@code break_glass_events}, so deleting a user or pipeline never erases the record.
 */
@Entity
@Table(name = "deployment_rollback_reviews")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DeploymentRollbackReviewEntity {

    @Id
    private UUID id;

    @Column(name = "deployment_request_id", nullable = false, unique = true)
    private UUID deploymentRequestId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "pipeline_id", nullable = false)
    private UUID pipelineId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(name = "outcome_detail", columnDefinition = "text")
    private String outcomeDetail;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "deployment_rollback_review_status")
    private DeploymentRollbackReviewStatus status = DeploymentRollbackReviewStatus.PENDING_REVIEW;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "review_comment", columnDefinition = "text")
    private String reviewComment;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Version
    @Column(name = "version_lock", nullable = false)
    private long versionLock;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
