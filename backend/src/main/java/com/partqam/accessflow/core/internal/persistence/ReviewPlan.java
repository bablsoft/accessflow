package com.partqam.accessflow.core.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_plans")
@Getter
@Setter
@NoArgsConstructor
public class ReviewPlan {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "requires_ai_review", nullable = false)
    private boolean requiresAiReview = true;

    @Column(name = "requires_human_approval", nullable = false)
    private boolean requiresHumanApproval = true;

    @Column(name = "min_approvals_required", nullable = false)
    private int minApprovalsRequired = 1;

    @Column(name = "approval_timeout_hours", nullable = false)
    private int approvalTimeoutHours = 24;

    @Column(name = "auto_approve_reads", nullable = false)
    private boolean autoApproveReads = false;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "notify_channels", columnDefinition = "text[]")
    private String[] notifyChannels;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
