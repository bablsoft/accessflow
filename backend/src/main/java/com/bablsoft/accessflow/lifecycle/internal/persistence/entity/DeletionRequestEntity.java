package com.bablsoft.accessflow.lifecycle.internal.persistence.entity;

import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.api.LifecycleSubjectType;
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

@Entity
@Table(name = "deletion_requests")
@Getter
@Setter
@NoArgsConstructor
public class DeletionRequestEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "subject_type", nullable = false, columnDefinition = "lifecycle_subject_type")
    private LifecycleSubjectType subjectType;

    @Column(name = "subject_identifier", nullable = false, columnDefinition = "text")
    private String subjectIdentifier;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "erasure_status")
    private ErasureStatus status = ErasureStatus.PENDING_SCOPE_AI;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "ai_scope_analysis_id")
    private UUID aiScopeAnalysisId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope_snapshot", columnDefinition = "jsonb")
    private String scopeSnapshot;

    @Column(name = "estimated_rows")
    private Long estimatedRows;

    @Column(name = "affected_rows")
    private Long affectedRows;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

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
