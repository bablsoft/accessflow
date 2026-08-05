package com.bablsoft.accessflow.ai.internal.persistence.entity;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-organization approval-outcome prediction model (AF-645). {@code coefficients} is a JSONB
 * blob holding the trained logistic regression as a unit — intercept, per-feature weights, and the
 * z-score standardization means/stddevs. Rewritten daily by the retrain job; {@code serving} is
 * true only when the quality gate (sample counts + holdout AUC) passed.
 */
@Entity
@Table(name = "approval_prediction_model")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class ApprovalPredictionModelEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private UUID organizationId;

    @Column(name = "feature_schema_version", nullable = false)
    private int featureSchemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String coefficients = "{}";

    @Column(name = "training_samples", nullable = false)
    private int trainingSamples;

    @Column(name = "positive_samples", nullable = false)
    private int positiveSamples;

    @Column
    private Double auc;

    @Column
    private Double accuracy;

    @Column(nullable = false)
    private boolean serving = false;

    @Column(name = "trained_at", nullable = false)
    private Instant trainedAt;

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
