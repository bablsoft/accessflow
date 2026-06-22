package com.bablsoft.accessflow.ai.internal.persistence.entity;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
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
 * A detected behavioural deviation (UBA, AF-383): which feature deviated, the deviation magnitude,
 * the observed vs baseline context (in {@code detail}), an optional fail-safe AI explanation, and
 * the acknowledge/dismiss lifecycle. Built only from {@code audit_log} metadata.
 */
@Entity
@Table(name = "behavior_anomaly")
@Getter
@Setter
@NoArgsConstructor
public class BehaviorAnomalyEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(nullable = false, columnDefinition = "text")
    private String feature;

    @Column(nullable = false)
    private double score;

    @Column(name = "observed_value")
    private Double observedValue;

    @Column(name = "baseline_mean")
    private Double baselineMean;

    @Column(name = "baseline_stddev")
    private Double baselineStddev;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String detail = "{}";

    @Column(name = "ai_summary", columnDefinition = "text")
    private String aiSummary;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "behavior_anomaly_status")
    private BehaviorAnomalyStatus status = BehaviorAnomalyStatus.OPEN;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();

    @Column(name = "acknowledged_by")
    private UUID acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

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
