package com.bablsoft.accessflow.discovery.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
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

@Entity
@Table(name = "discovery_finding")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DiscoveryFindingEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    // null for engines without a schema concept (COALESCE'd to '' in the unique index).
    @Column(name = "schema_name", columnDefinition = "text")
    private String schemaName;

    @Column(name = "table_name", nullable = false, columnDefinition = "text")
    private String tableName;

    @Column(name = "column_name", nullable = false, columnDefinition = "text")
    private String columnName;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "data_classification")
    private DataClassification classification;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "discovery_detector")
    private DiscoveryDetector detector;

    @Column(name = "confidence", nullable = false)
    private int confidence;

    // Redacted evidence only — raw sampled values never persist.
    @Column(name = "sample_redacted", columnDefinition = "text")
    private String sampleRedacted;

    // AI detector only.
    @Column(name = "rationale", columnDefinition = "text")
    private String rationale;

    @Column(name = "match_count", nullable = false)
    private int matchCount;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "discovery_finding_status")
    private DiscoveryFindingStatus status = DiscoveryFindingStatus.PENDING;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "first_detected_at", nullable = false)
    private Instant firstDetectedAt;

    @Column(name = "last_detected_at", nullable = false)
    private Instant lastDetectedAt;

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
