package com.bablsoft.accessflow.schemachange.internal.persistence.entity;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

/**
 * One drift scan of one environment (#878). Every reference is a bare id (no FK). Written by the
 * drift job (#881); {@code applicable = false} records a sampling engine that was not diffed and
 * {@code partial = true} a scan cut short by the table cap or time budget.
 */
@Entity
@Table(name = "schema_drift_scans")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class SchemaDriftScanEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "pipeline_id", nullable = false)
    private UUID pipelineId;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "schema_drift_baseline")
    private SchemaDriftBaseline baseline;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(nullable = false)
    private boolean applicable = true;

    @Column(name = "findings_count", nullable = false)
    private int findingsCount;

    @Column(nullable = false)
    private boolean partial;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
