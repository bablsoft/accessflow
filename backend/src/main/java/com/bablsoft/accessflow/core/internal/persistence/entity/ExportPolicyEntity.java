package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.ExportPolicyMode;
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
@Table(name = "export_policy")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class ExportPolicyEntity {

    @Id
    private UUID id;

    // Bare UUID references — export_policy is configuration scoped to a datasource; the
    // resolution path queries by these ids directly and the admin service validates the
    // datasource-in-organization invariant via DatasourceRepository.
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "export_policy_mode")
    private ExportPolicyMode mode;

    @Column(name = "row_cap")
    private Integer rowCap;

    // data_classification enum NAMES as text[] (the applies_to_roles precedent) — Hibernate's
    // array binding for PG enum element types is unreliable; the admin service validates values
    // against core.api.DataClassification.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "deny_classifications", columnDefinition = "text[]")
    private String[] denyClassifications;

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
