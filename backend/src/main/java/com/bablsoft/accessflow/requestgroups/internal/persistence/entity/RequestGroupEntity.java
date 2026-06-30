package com.bablsoft.accessflow.requestgroups.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
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
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "request_groups")
@Getter
@Setter
@NoArgsConstructor
public class RequestGroupEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "request_group_status")
    private RequestGroupStatus status = RequestGroupStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "submission_reason", nullable = false, columnDefinition = "submission_reason")
    private SubmissionReason submissionReason = SubmissionReason.USER_SUBMITTED;

    @Column(name = "continue_on_error", nullable = false)
    private boolean continueOnError = false;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "ai_risk_level", columnDefinition = "risk_level")
    private RiskLevel aiRiskLevel;

    @Column(name = "ai_risk_score")
    private Integer aiRiskScore;

    @Column(name = "required_approvals", nullable = false)
    private int requiredApprovals = 1;

    @Column(name = "current_review_stage", nullable = false)
    private int currentReviewStage = 1;

    @Column(name = "submitted_ip", length = 64)
    private String submittedIp;

    @Column(name = "submitted_user_agent", columnDefinition = "text")
    private String submittedUserAgent;

    @Column(name = "execution_started_at")
    private Instant executionStartedAt;

    @Column(name = "execution_completed_at")
    private Instant executionCompletedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
