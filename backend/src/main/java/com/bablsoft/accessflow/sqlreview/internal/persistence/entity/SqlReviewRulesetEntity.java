package com.bablsoft.accessflow.sqlreview.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
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
 * A SQL review ruleset (#861). Bound to one {@link DatasourceEnvironment}, or — with a null
 * environment — the organization-wide default. Uniqueness of both shapes is enforced by the two
 * partial indexes in {@code V170}; the organization is a bare id (the cross-module convention).
 */
@Entity
@Table(name = "sql_review_rulesets")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class SqlReviewRulesetEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Null = the organization-wide default ruleset. */
    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(columnDefinition = "datasource_environment")
    private DatasourceEnvironment environment;

    @Column(nullable = false)
    private boolean enabled = true;

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
