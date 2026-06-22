package com.bablsoft.accessflow.ai.internal.persistence.entity;

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
 * Rolling behavioural baseline for one (org, user, datasource) — UBA (AF-383). {@code features} is
 * a JSONB blob (serialized {@code BaselineProfile}) holding bounded per-feature observation windows,
 * a cumulative active-hour histogram, and query-type / table frequency maps. Built only from
 * {@code audit_log} metadata.
 */
@Entity
@Table(name = "behavior_baseline")
@Getter
@Setter
@NoArgsConstructor
public class BehaviorBaselineEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String features = "{}";

    @Column(name = "sample_size", nullable = false)
    private int sampleSize;

    @Column(name = "last_window_start")
    private Instant lastWindowStart;

    @Column(name = "last_refreshed_at", nullable = false)
    private Instant lastRefreshedAt = Instant.now();

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
