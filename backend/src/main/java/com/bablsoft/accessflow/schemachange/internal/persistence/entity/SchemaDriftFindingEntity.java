package com.bablsoft.accessflow.schemachange.internal.persistence.entity;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingKind;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingStatus;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
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

/**
 * One drifted object path, owned by the scan that last observed it (#878). Organization and
 * environment are bare ids (no FK); the scan link is the one real cascade.
 */
@Entity
@Table(name = "schema_drift_findings")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class SchemaDriftFindingEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_id", nullable = false)
    private SchemaDriftScanEntity scan;

    @Column(name = "environment_id", nullable = false)
    private UUID environmentId;

    @Column(name = "object_path", nullable = false, length = 1024)
    private String objectPath;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "finding_kind", nullable = false, columnDefinition = "schema_drift_finding_kind")
    private SchemaDriftFindingKind findingKind;

    @Column(name = "expected_value", columnDefinition = "TEXT")
    private String expectedValue;

    @Column(name = "actual_value", columnDefinition = "TEXT")
    private String actualValue;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "schema_drift_finding_status")
    private SchemaDriftFindingStatus status = SchemaDriftFindingStatus.OPEN;

    @Column(name = "first_detected_at", nullable = false, updatable = false)
    private Instant firstDetectedAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** V181: a scan's stale write must never land over an acknowledgement made while it ran. */
    @Version
    @Column(nullable = false)
    private long version;
}
