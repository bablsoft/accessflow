package com.bablsoft.accessflow.core.internal.persistence.entity;

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

@Entity
@Table(name = "row_limit_policy")
@Getter
@Setter
@NoArgsConstructor
public class RowLimitPolicyEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(name = "schema_name")
    private String schemaName;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "max_rows", nullable = false)
    private int maxRows;

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
