package com.bablsoft.accessflow.schemachange.internal.persistence.entity;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
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
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-pipeline schema drift configuration (#881, V180). The drift job drains this table — deploygov
 * exposes no cross-organization pipeline listing, so an opt-in row is what makes a scheduled scan
 * discoverable at all (the {@code discovery_scan_config} precedent).
 *
 * <p>Every reference is a bare id (no FK). Absence of a row, like {@code enabled = false}, means
 * drift is off for that pipeline.
 */
@Entity
@Table(name = "schema_drift_configs")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class SchemaDriftConfigEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "pipeline_id", nullable = false, unique = true)
    private UUID pipelineId;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "schema_drift_baseline")
    private SchemaDriftBaseline baseline = SchemaDriftBaseline.PREVIOUS_ENVIRONMENT;

    /** Only read when {@link #baseline} is {@code BASELINE_ENVIRONMENT}. */
    @Column(name = "baseline_environment_id")
    private UUID baselineEnvironmentId;

    @Column(name = "scan_interval_hours", nullable = false)
    private int scanIntervalHours = 24;

    @Column(name = "last_scan_at")
    private Instant lastScanAt;

    @Column(name = "last_scan_error", columnDefinition = "TEXT")
    private String lastScanError;

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
