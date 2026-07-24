package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "query_requests")
@Getter
@Setter
@NoArgsConstructor
public class QueryRequestEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "datasource_id", nullable = false)
    private DatasourceEntity datasource;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submitted_by", nullable = false)
    private UserEntity submittedBy;

    @Column(name = "sql_text", nullable = false, columnDefinition = "text")
    private String sqlText;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "query_type", nullable = false, columnDefinition = "query_type")
    private QueryType queryType;

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

    // FK to ai_analyses — stored as bare UUID to break the circular entity reference;
    // the DB constraint is added in V7 after ai_analyses is created.
    @Column(name = "ai_analysis_id")
    private UUID aiAnalysisId;

    // FK to query_estimates (AF-624) — bare UUID for the same circular-reference reason;
    // the DB constraint is added in V128 after query_estimates is created.
    @Column(name = "query_estimate_id")
    private UUID queryEstimateId;

    // Bare UUID to access_grant_request (#582) — the grant row's lifecycle (expiry, revocation)
    // is independent of this query's audit trail, so no FK.
    @Column(name = "approved_by_grant_id")
    private UUID approvedByGrantId;

    @Column(name = "execution_started_at")
    private Instant executionStartedAt;

    @Column(name = "execution_completed_at")
    private Instant executionCompletedAt;

    @Column(name = "rows_affected")
    private Long rowsAffected;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "execution_duration_ms")
    private Integer executionDurationMs;

    @Column(name = "transactional", nullable = false)
    private boolean transactional = false;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "previous_run_id")
    private UUID previousRunId;

    @Column(name = "canonical_sql", columnDefinition = "text")
    private String canonicalSql;

    // Client context captured at submission (the only point an HTTP request exists); read back when
    // routing policies evaluate asynchronously after AI completion. See AF-446.
    @Column(name = "submitted_ip", length = 45)
    private String submittedIp;

    @Column(name = "submitted_user_agent", columnDefinition = "text")
    private String submittedUserAgent;

    @Column(name = "cicd_origin", nullable = false)
    private boolean ciCdOrigin = false;

    @Version
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
