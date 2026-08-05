package com.bablsoft.accessflow.core.internal.persistence.entity;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
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

/**
 * Advisory approval-likelihood prediction for one query request (AF-645). Write-once, persisted
 * when the query lands in review. {@code probability} is null on the {@code skipped} ("not enough
 * history yet") and {@code failed} sentinel rows; {@code features} snapshots the serving-time
 * feature vector for explainability.
 */
@Entity
@Table(name = "approval_predictions")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class ApprovalPredictionEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "query_request_id", nullable = false)
    private QueryRequestEntity queryRequest;

    @Column
    private Double probability;

    @Column(name = "model_id")
    private UUID modelId;

    @Column(name = "feature_schema_version")
    private Integer featureSchemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String features;

    @Column(nullable = false)
    private boolean skipped = false;

    @Column(name = "skipped_reason", length = 500)
    private String skippedReason;

    @Column(nullable = false)
    private boolean failed = false;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
