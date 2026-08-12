package com.bablsoft.accessflow.access.internal.persistence.entity;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
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
 * Materialised usage evidence for one live standing grant (#625). A derived cache reconciled against
 * the live grants each aggregation tick, not a record — see {@code V135__create_grant_usage.sql}.
 */
@Entity
@Table(name = "grant_usage_summary")
@Getter
@Setter
@NoArgsConstructor
public class GrantUsageSummaryEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "resource_kind", nullable = false, columnDefinition = "grant_resource_kind")
    private GrantResourceKind resourceKind;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "resource_name", nullable = false, columnDefinition = "text")
    private String resourceName;

    @Column(name = "permission_id", nullable = false)
    private UUID permissionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_email", nullable = false, columnDefinition = "text")
    private String userEmail;

    @Column(name = "user_display_name", columnDefinition = "text")
    private String userDisplayName;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Null means unrestricted (the grant allows every table / operation) — not zero. */
    @Column(name = "granted_target_count")
    private Integer grantedTargetCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "used_targets", nullable = false, columnDefinition = "jsonb")
    private String usedTargets = "[]";

    @Column(name = "used_target_count", nullable = false)
    private int usedTargetCount = 0;

    @Column(name = "usage_count", nullable = false)
    private long usageCount = 0;

    @Column(name = "first_used_at")
    private Instant firstUsedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "observed_since", nullable = false)
    private Instant observedSince;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "grant_usage_recommendation")
    private GrantUsageRecommendation recommendation = GrantUsageRecommendation.INSUFFICIENT_DATA;

    @Column(name = "nudged_at")
    private Instant nudgedAt;

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
