package com.bablsoft.accessflow.workflow.internal.persistence.entity;

import com.bablsoft.accessflow.workflow.api.BreakGlassStatus;
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
 * The mandatory retro-review opened by a break-glass execution (AF-385). One row per break-glass
 * query (unique {@code query_request_id}); an admin reconciles it from {@code PENDING_REVIEW} to
 * {@code REVIEWED}. {@code submitted_by} / {@code reviewed_by} / {@code organization_id} /
 * {@code datasource_id} are bare UUIDs (no FK), like {@code query_snapshots}, so deleting a user
 * never erases the record.
 */
@Entity
@Table(name = "break_glass_events")
@Getter
@Setter
@NoArgsConstructor
public class BreakGlassEventEntity {

    @Id
    private UUID id;

    @Column(name = "query_request_id", nullable = false)
    private UUID queryRequestId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(nullable = false, columnDefinition = "text")
    private String justification;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "break_glass_status")
    private BreakGlassStatus status = BreakGlassStatus.PENDING_REVIEW;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "review_comment", columnDefinition = "text")
    private String reviewComment;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
