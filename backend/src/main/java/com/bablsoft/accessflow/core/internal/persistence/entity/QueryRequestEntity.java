package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
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

    @Column(columnDefinition = "text")
    private String justification;

    // FK to ai_analyses — stored as bare UUID to break the circular entity reference;
    // the DB constraint is added in V7 after ai_analyses is created.
    @Column(name = "ai_analysis_id")
    private UUID aiAnalysisId;

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

    @Version
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
