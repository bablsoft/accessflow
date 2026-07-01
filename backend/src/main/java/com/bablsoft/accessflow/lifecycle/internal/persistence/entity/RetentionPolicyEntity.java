package com.bablsoft.accessflow.lifecycle.internal.persistence.entity;

import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecycleTransform;
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

@Entity
@Table(name = "retention_policies")
@Getter
@Setter
@NoArgsConstructor
public class RetentionPolicyEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "target_table", columnDefinition = "text")
    private String targetTable;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "target_columns", columnDefinition = "text[]")
    private String[] targetColumns;

    @Column(name = "classification_tag", columnDefinition = "text")
    private String classificationTag;

    @Column(name = "timestamp_column", nullable = false, columnDefinition = "text")
    private String timestampColumn;

    @Column(name = "retention_window", nullable = false, columnDefinition = "text")
    private String retentionWindow;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "lifecycle_action")
    private LifecycleAction action;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "transform_type", columnDefinition = "lifecycle_transform")
    private LifecycleTransform transformType;

    @Column(name = "soft_delete_column", columnDefinition = "text")
    private String softDeleteColumn;

    // AF-519: structured predicate list (JSONB) — { "match_type": "ALL", "conditions": [...] }.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String conditions;

    // AF-519: raw WHERE escape hatch (admin-authored, JSqlParser-validated).
    @Column(name = "raw_where", columnDefinition = "text")
    private String rawWhere;

    // AF-519: optional per-policy cron schedule, decoupled from the global scan interval.
    @Column(name = "cron_schedule", columnDefinition = "text")
    private String cronSchedule;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

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
