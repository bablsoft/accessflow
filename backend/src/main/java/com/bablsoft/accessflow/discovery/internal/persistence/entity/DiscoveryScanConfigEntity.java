package com.bablsoft.accessflow.discovery.internal.persistence.entity;

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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "discovery_scan_config")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DiscoveryScanConfigEntity {

    @Id
    private UUID id;

    // Bare UUID references — discovery config is datasource-scoped child config; the services
    // validate the datasource-in-organization invariant via DatasourceAdminService.
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "datasource_id", nullable = false, unique = true)
    private UUID datasourceId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "sample_size", nullable = false)
    private int sampleSize = 100;

    @Column(name = "scan_interval_hours", nullable = false)
    private int scanIntervalHours = 24;

    @Column(name = "ai_classification_enabled", nullable = false)
    private boolean aiClassificationEnabled = false;

    @Column(name = "last_scan_at")
    private Instant lastScanAt;

    @Column(name = "last_scan_error", columnDefinition = "text")
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
