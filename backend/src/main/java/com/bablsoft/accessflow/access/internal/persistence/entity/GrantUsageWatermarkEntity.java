package com.bablsoft.accessflow.access.internal.persistence.entity;

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

/**
 * Per-organization cursor into {@code audit_log} for the grant-usage fold (#625). Deliberately not a
 * column on {@link GrantUsageSummaryEntity}: the fold is one org-scoped range read per tick, so the
 * cursor belongs to the organization, and a summary row created after the cursor advanced needs an
 * explicit backfill rather than an inherited timestamp that would silently hide the gap.
 */
@Entity
@Table(name = "grant_usage_watermark")
@Getter
@Setter
@NoArgsConstructor
public class GrantUsageWatermarkEntity {

    @Id
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "aggregated_through", nullable = false)
    private Instant aggregatedThrough;

    /** The audit row the fold stopped after; the nil UUID means "start of that instant". */
    @Column(name = "aggregated_through_id", nullable = false)
    private UUID aggregatedThroughId = new UUID(0L, 0L);

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
