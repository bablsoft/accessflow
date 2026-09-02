package com.bablsoft.accessflow.deploygov.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcome;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A governed deployment request. {@code version} is the semantic artifact version being deployed
 * ("2.4.1"); the optimistic-lock column is {@code version_lock} to avoid the collision.
 * {@code status} reuses the shared {@code query_status} lifecycle exactly as {@code api_requests}
 * does; {@code outcome} records what the pipeline reported after the gate opened.
 */
@Entity
@Table(name = "deployment_requests")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DeploymentRequestEntity {

    @Id
    private UUID id;

    @Column(name = "pipeline_id", nullable = false)
    private UUID pipelineId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(nullable = false, length = 255)
    private String version;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "artifact_ref", columnDefinition = "text")
    private String artifactRef;

    @Column(name = "run_url", columnDefinition = "text")
    private String runUrl;

    @Column(name = "external_run_id", columnDefinition = "text")
    private String externalRunId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata = "{}";

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "query_status")
    private QueryStatus status = QueryStatus.PENDING_AI;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "submission_reason", nullable = false, columnDefinition = "submission_reason")
    private SubmissionReason submissionReason = SubmissionReason.USER_SUBMITTED;

    @Column(columnDefinition = "text")
    private String justification;

    @Column(name = "ai_analysis_id")
    private UUID aiAnalysisId;

    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals = 1;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "deployment_outcome")
    private DeploymentOutcome outcome;

    @Column(name = "outcome_reported_at")
    private Instant outcomeReportedAt;

    // #742: stamped once on the APPROVED → EXECUTED transition; the drift math's time axis.
    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "outcome_detail", columnDefinition = "text")
    private String outcomeDetail;

    @Column(name = "release_notified_at")
    private Instant releaseNotifiedAt;

    @Column(name = "submitted_ip", length = 45)
    private String submittedIp;

    @Version
    @Column(name = "version_lock", nullable = false)
    private long versionLock;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
