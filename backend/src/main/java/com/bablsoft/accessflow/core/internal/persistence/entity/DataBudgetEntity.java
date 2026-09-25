package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "data_budgets")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DataBudgetEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "max_rows")
    private Long maxRows;

    @Column(name = "max_bytes")
    private Long maxBytes;

    @Column(name = "window_minutes", nullable = false)
    private int windowMinutes;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "breach_action", nullable = false, columnDefinition = "data_budget_breach_action")
    private DataBudgetBreachAction breachAction;

    @Column(name = "warn_threshold_percent")
    private Short warnThresholdPercent;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "applies_to_roles", columnDefinition = "text[]")
    private String[] appliesToRoles;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "applies_to_group_ids", columnDefinition = "uuid[]")
    private UUID[] appliesToGroupIds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "applies_to_user_ids", columnDefinition = "uuid[]")
    private UUID[] appliesToUserIds;

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
