package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
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
@Table(name = "row_security_policy")
@Getter
@Setter
@NoArgsConstructor
public class RowSecurityPolicyEntity {

    @Id
    private UUID id;

    // Bare UUID references — row_security_policy is configuration scoped to a datasource; the
    // resolution path queries by these ids directly and the admin service validates the
    // datasource-in-organization invariant via DatasourceRepository.
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(name = "table_name", nullable = false, columnDefinition = "text")
    private String tableName;

    @Column(name = "column_name", nullable = false, columnDefinition = "text")
    private String columnName;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "row_security_operator")
    private RowSecurityOperator operator;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "value_type", nullable = false, columnDefinition = "row_security_value_type")
    private RowSecurityValueType valueType;

    @Column(name = "value_expression", nullable = false, columnDefinition = "text")
    private String valueExpression;

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
